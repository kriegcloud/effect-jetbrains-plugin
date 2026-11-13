package dev.effect.intellij.lsp

import com.intellij.lang.ecmascript6.psi.ES6FromClause
import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.ecmascript6.psi.ES6ImportExportSpecifier
import com.intellij.lang.ecmascript6.psi.ES6ImportExportSpecifierAlias
import com.intellij.lang.ecmascript6.psi.ES6ImportedBinding
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSExpression
import com.intellij.lang.javascript.psi.JSNewExpression
import com.intellij.lang.javascript.psi.JSParenthesizedExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.javascript.psi.JSVariable
import com.intellij.lang.javascript.psi.ecma6.TypeScriptSingleType
import com.intellij.lang.javascript.psi.ecma6.TypeScriptTypeArgumentList
import com.intellij.lang.javascript.psi.ecma6.TypeScriptVariable
import com.intellij.lang.javascript.psi.ecmal4.JSClass
import com.intellij.lang.javascript.psi.stubs.JSClassIndex
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.indexing.DumbModeAccessType
import dev.effect.intellij.core.logger
import org.eclipse.lsp4j.Diagnostic
import java.util.concurrent.ConcurrentHashMap

/** The `@effect/tsgo` rule that suggests `X.make(...)` in place of `new X(...)`. */
internal const val NEW_SCHEMA_CLASS_RULE = "newSchemaClass"

/**
 * Whether the class a `new` expression constructs hands itself to its Schema factory as `Self`.
 *
 * Effect types `make` as returning `Self`, the type argument of `Schema.Class<Self>(...)`,
 * `Schema.TaggedErrorClass<Self>()(...)`, `Base.extend<Self>(...)` and the like. TypeScript cannot infer
 * the class being declared, so `make` returns the class's own instance type only when the declaration
 * names itself there.
 */
internal enum class EffectSchemaClassSelf {
    /** `class A extends Schema.Class<A>("A")({...}) {}`: `A.make(...)` and `new A(...)` agree. */
    DECLARED,

    /**
     * The class is declared without itself as a type argument, so `make` returns another type than
     * `new` does: a factory that fills `Self` with a structural type
     * (`class NotAMember extends Domain.Rejection("NotAMember") {}`), or a plain subclass of a Schema
     * class (`class B extends A {}`, where `B.make(...)` returns an `A`).
     */
    ABSENT,

    /** The declaration could not be read (unresolved reference, no class declaration). */
    UNKNOWN,
}

/**
 * True for a `newSchemaClass` diagnostic that cannot be satisfied.
 *
 * `@effect/tsgo` (0.67.4 through 0.69.0, `internal/rules/new_schema_class.go`) reports every
 * constructable Schema type that has a `make` property, without comparing what `make` returns with
 * what `new` returns. For a class in the [EffectSchemaClassSelf.ABSENT] state the suggested rewrite
 * changes the expression's type; with `@alchemy.run/domain` rejections it does not compile
 * (`Type 'Instance<"NotAMember", {}>' is missing the following properties from type 'NotAMember'`).
 * Such a diagnostic is dropped, together with its quick fix.
 *
 * [calleeRange] is the diagnostic's range: the expression after `new`. Call under read access.
 */
internal fun Diagnostic.isUnsatisfiableNewSchemaClass(file: PsiFile, calleeRange: TextRange): Boolean =
    effectDiagnosticRuleName() == NEW_SCHEMA_CLASS_RULE &&
        constructedSchemaClassSelf(file, calleeRange) == EffectSchemaClassSelf.ABSENT

/**
 * Classifies the class constructed by the `new` expression whose callee covers [calleeRange].
 *
 * The platform asks while the project is indexing and before an edit is committed to the PSI: on the
 * 2026.3 line it applies diagnostics once, when the server sends them, and does not ask again when
 * indexing ends. Answering [EffectSchemaClassSelf.UNKNOWN] then would leave a dropped diagnostic on
 * screen until the next edit. So the declaration is read through the index data that stays reliable
 * while indexing, an answer that cannot be read right now is taken from the last one given for the
 * same expression in the file, and when there is none either, [reapply] is called once the project can
 * answer, so that the platform asks again.
 */
internal fun constructedSchemaClassSelf(
    file: PsiFile,
    calleeRange: TextRange,
    reapply: (PsiFile) -> Unit = EffectLspDiagnosticsRefresh::reapply,
): EffectSchemaClassSelf {
    val project = file.project
    val documents = PsiDocumentManager.getInstance(project)
    val document = documents.getCachedDocument(file)
    val text = document?.immutableCharSequence ?: file.viewProvider.contents
    if (calleeRange.isEmpty || calleeRange.startOffset < 0 || calleeRange.endOffset > text.length) {
        return EffectSchemaClassSelf.UNKNOWN
    }
    val calleeText = calleeRange.subSequence(text).toString()
    // Diagnostic offsets belong to the document; they address the PSI only once it is committed. Until
    // then the same expression is looked up by its text.
    val committed = document == null || documents.isCommitted(document)
    val callee = if (committed) constructedExpression(file, calleeRange) else constructedExpression(file, calleeText)
    if (callee == null && committed) {
        traceNoCallee(file, calleeRange, calleeText)
        return EffectSchemaClassSelf.UNKNOWN
    }
    val read = (callee as? JSReferenceExpression)?.let(::readSelf)
    // While indexing, "no declaration found" can mean "not indexed yet".
    val answer = read?.takeUnless { it == EffectSchemaClassSelf.UNKNOWN && DumbService.isDumb(project) }
    val remembered = file.rememberedSelves()
    if (answer != null) {
        if (remembered.size >= MAX_REMEMBERED_SELVES) {
            remembered.clear()
        }
        remembered[calleeText] = answer
        return answer
    }
    val last = remembered[calleeText]
    if (last == null) {
        askAgainWhenReadable(file, calleeText, committed, callee, reapply)
    }
    return last ?: EffectSchemaClassSelf.UNKNOWN
}

/** `null` when the indexes cannot be read at all right now. */
private fun readSelf(callee: JSReferenceExpression): EffectSchemaClassSelf? =
    try {
        DumbModeAccessType.RELIABLE_DATA_ONLY.ignoreDumbMode(
            ThrowableComputable<EffectSchemaClassSelf, RuntimeException> {
                // A class merged with a namespace or an interface resolves to all of them; only the class decides.
                val declarations = declarationsOf(callee)
                declarations.filterIsInstance<JSClass>().filterNot { it.isInterface }.map(::selfOf).combined()
                    .also { traceKept(callee, it, declarations) }
            },
        )
    } catch (_: IndexNotReadyException) {
        null
    }

private fun constructedExpression(file: PsiFile, calleeRange: TextRange): JSExpression? {
    val newExpression = PsiTreeUtil.getParentOfType(file.findElementAt(calleeRange.startOffset), JSNewExpression::class.java)
    val callee = newExpression?.methodExpression ?: return null
    return callee.takeIf { it.textRange.contains(calleeRange) }?.withoutParentheses()
}

/** The callee of any `new` expression in [file] written as [calleeText]: the same name is the same class. */
private fun constructedExpression(file: PsiFile, calleeText: String): JSExpression? =
    PsiTreeUtil.findChildrenOfType(file, JSNewExpression::class.java)
        .firstNotNullOfOrNull { it.methodExpression?.takeIf { callee -> callee.textMatches(calleeText) } }
        ?.withoutParentheses()

/** The last answer given for each constructed expression of a file, by its text. */
private fun PsiFile.rememberedSelves(): MutableMap<String, EffectSchemaClassSelf> {
    val holder = viewProvider.virtualFile
    return holder.getUserData(rememberedSelvesKey) ?: synchronized(holder) {
        holder.getUserData(rememberedSelvesKey)
            ?: ConcurrentHashMap<String, EffectSchemaClassSelf>().also { holder.putUserData(rememberedSelvesKey, it) }
    }
}

/**
 * A few times per expression at most: says in `idea.log` why a `newSchemaClass` diagnostic is shown although its class
 * could not be read, and has the diagnostics applied again when the cause is over (the edit is
 * committed, indexing has ended).
 */
private fun askAgainWhenReadable(
    file: PsiFile,
    calleeText: String,
    committed: Boolean,
    callee: JSExpression?,
    reapply: (PsiFile) -> Unit,
) {
    val project = file.project
    val path = file.viewProvider.virtualFile.path
    if (unreadAskedAgain.size >= MAX_UNREAD_EXPRESSIONS) {
        return
    }
    // Bounded, so that an expression that stays unreadable cannot keep requesting diagnostics.
    if (unreadAskedAgain.merge("$path\u0000$calleeText", 1, Int::plus)!! > MAX_ASKS_PER_EXPRESSION) {
        return
    }
    // One request covers every expression of the file that is waiting for the same thing.
    val holder = file.viewProvider.virtualFile
    if (holder.getUserData(askingAgainKey) == true) {
        return
    }
    holder.putUserData(askingAgainKey, true)
    val reason = when {
        !committed && callee == null -> "the edit is not committed and no `new $calleeText` is in the PSI yet"
        DumbService.isDumb(project) -> "no class declaration can be read while indexing"
        else -> "the indexes cannot be read"
    }
    guardLog.info("newSchemaClass is shown for `new $calleeText` in $path: $reason; it is decided again afterwards")
    ApplicationManager.getApplication().invokeLater(
        {
            val again = Runnable {
                holder.putUserData(askingAgainKey, null)
                reapply(file)
            }
            if (committed) {
                DumbService.getInstance(project).runWhenSmart(again)
            } else {
                PsiDocumentManager.getInstance(project).performLaterWhenAllCommitted { DumbService.getInstance(project).runWhenSmart(again) }
            }
        },
        project.disposed,
    )
}

/**
 * Says once per expression what a `newSchemaClass` diagnostic that stays was decided from. A diagnostic
 * that should have been dropped is then explained by `idea.log`: what the IDE resolves a reference to
 * depends on its TypeScript service and cannot be reproduced outside it.
 */
private fun traceKept(callee: JSReferenceExpression, self: EffectSchemaClassSelf, declarations: List<PsiElement>) {
    if (self == EffectSchemaClassSelf.ABSENT || keptTraced.size >= MAX_UNREAD_EXPRESSIONS) {
        return
    }
    val path = callee.containingFile?.viewProvider?.virtualFile?.path
    if (!keptTraced.add("$path\u0000${callee.text}\u0000$self")) {
        return
    }
    val seen = declarations.joinToString { "${it.javaClass.simpleName} in ${it.containingFile?.name}" }
    guardLog.info("newSchemaClass is shown for `new ${callee.text}` in $path: $self, read from [$seen]")
}

private fun traceNoCallee(file: PsiFile, calleeRange: TextRange, calleeText: String) {
    val path = file.viewProvider.virtualFile.path
    if (keptTraced.size >= MAX_UNREAD_EXPRESSIONS || !keptTraced.add("$path\u0000$calleeText\u0000no callee")) {
        return
    }
    val leaf = file.findElementAt(calleeRange.startOffset)
    val newExpression = PsiTreeUtil.getParentOfType(leaf, JSNewExpression::class.java)
    guardLog.info(
        "newSchemaClass is shown for `$calleeText` at $calleeRange in $path: not the callee of a `new` expression " +
            "(${file.javaClass.simpleName}, ${leaf?.javaClass?.simpleName} in ${leaf?.parent?.javaClass?.simpleName}, " +
            "new at ${newExpression?.textRange}, callee at ${newExpression?.methodExpression?.textRange})",
    )
}

private val guardLog = logger<EffectSchemaClassSelf>()
private val keptTraced: MutableSet<String> = ConcurrentHashMap.newKeySet()
private val rememberedSelvesKey = Key.create<MutableMap<String, EffectSchemaClassSelf>>("effect.newSchemaClass.selves")
private val unreadAskedAgain = ConcurrentHashMap<String, Int>()
private val askingAgainKey = Key.create<Boolean>("effect.newSchemaClass.askingAgain")

/** A file with more constructed expressions than this starts over; the entries are recomputed on demand. */
private const val MAX_REMEMBERED_SELVES = 512
private const val MAX_UNREAD_EXPRESSIONS = 512
private const val MAX_ASKS_PER_EXPRESSION = 3

private fun selfOf(schemaClass: JSClass): EffectSchemaClassSelf {
    val name = schemaClass.name ?: return EffectSchemaClassSelf.UNKNOWN
    val heritage = schemaClass.extendsList?.members?.singleOrNull() ?: return EffectSchemaClassSelf.UNKNOWN
    if (heritage.hasTypeArgument(name)) {
        return EffectSchemaClassSelf.DECLARED
    }
    val base = heritage.expression?.withoutParentheses() ?: return EffectSchemaClassSelf.UNKNOWN
    return when {
        factorySpine(base).any { it.hasTypeArgument(name) } -> EffectSchemaClassSelf.DECLARED
        base is JSCallExpression -> absentUnlessSelfIsHandedOn(base, name)
        base is JSReferenceExpression -> selfOfBaseReference(base, name, MAX_BASE_ALIASES)
        else -> EffectSchemaClassSelf.UNKNOWN
    }
}

/**
 * A class that extends a bare reference gets its `make` from what the reference names: another class
 * (whose `make` returns that class), or a constant holding the factory result. Declaration files always
 * take the second form: `declare const A_base: Schema.Class<A, ...>; class A extends A_base {}`.
 * A constant can also be an alias of another one (`const B = A`), which is followed [aliases] times.
 * `null` for a declaration that says nothing about the base (a namespace or interface merged with it).
 */
private fun selfOfBase(base: PsiElement, name: String, aliases: Int): EffectSchemaClassSelf? = when (base) {
    is JSClass -> EffectSchemaClassSelf.ABSENT.takeUnless { base.isInterface }
    is JSVariable -> {
        val declaredType = (base as? TypeScriptVariable)?.typeElement
        val initializer = base.initializer?.withoutParentheses()
        when {
            declaredType != null && declaredType.namesAsTypeArgument(name) -> EffectSchemaClassSelf.DECLARED
            initializer != null && factorySpine(initializer).any { it.hasTypeArgument(name) } -> EffectSchemaClassSelf.DECLARED
            declaredType != null -> EffectSchemaClassSelf.ABSENT
            initializer is JSCallExpression -> absentUnlessSelfIsHandedOn(initializer, name)
            initializer is JSReferenceExpression -> selfOfBaseReference(initializer, name, aliases - 1)
            else -> EffectSchemaClassSelf.UNKNOWN
        }
    }
    else -> null
}

private fun selfOfBaseReference(base: JSReferenceExpression, name: String, aliases: Int): EffectSchemaClassSelf =
    if (aliases <= 0) {
        EffectSchemaClassSelf.UNKNOWN
    } else {
        declarationsOf(base).mapNotNull { selfOfBase(it, name, aliases) }.combined()
    }

/**
 * The calls a factory expression is made of, outermost first: `Schema.Class<A>("A")({...})` yields the
 * outer call, then `Schema.Class<A>("A")`. Arguments are left out on purpose, so a field that mentions
 * the class (`Schema.suspend((): Schema.Codec<Tree> => Tree)`) is not mistaken for `Self`.
 */
private fun factorySpine(expression: JSExpression): Sequence<JSExpression> =
    generateSequence(expression) { current ->
        when (current) {
            is JSCallExpression -> current.methodExpression
            is JSReferenceExpression -> current.qualifier
            is JSParenthesizedExpression -> current.innerExpression
            else -> null
        }
    }

/**
 * `wrap(Schema.Class<A>("A")({...}))` hands a factory result that carries `Self` to another call.
 * Whether `wrap` keeps its `make` cannot be read from the declaration, so the diagnostic stays.
 */
private fun absentUnlessSelfIsHandedOn(factory: JSCallExpression, name: String): EffectSchemaClassSelf =
    if (handsOnSelf(factory, name, MAX_WRAPPED_CALLS)) EffectSchemaClassSelf.UNKNOWN else EffectSchemaClassSelf.ABSENT

/** Only calls passed directly as arguments are looked at; a fields object or a thunk is not a factory result. */
private fun handsOnSelf(factory: JSExpression, name: String, depth: Int): Boolean =
    depth > 0 && factorySpine(factory).filterIsInstance<JSCallExpression>().any { call ->
        call.arguments.any { argument ->
            val handedOn = argument.withoutParentheses() as? JSCallExpression
            handedOn != null &&
                (factorySpine(handedOn).any { it.hasTypeArgument(name) } || handsOnSelf(handedOn, name, depth - 1))
        }
    }

/** `Schema.Class<A, ...>`, alone or as a member of an intersection or union. */
private fun PsiElement.namesAsTypeArgument(name: String): Boolean =
    hasTypeArgument(name) || children.any { it is TypeScriptSingleType && it.hasTypeArgument(name) }

private fun PsiElement.hasTypeArgument(name: String): Boolean =
    PsiTreeUtil.getChildOfType(this, TypeScriptTypeArgumentList::class.java)
        ?.typeArguments
        ?.any { argument ->
            // TypeScriptSingleType.getReferenceExpression is internal API; the reference is its child.
            // A qualifier is not compared: `Schema.Class<Models.Account>` inside `namespace Models` is
            // the class itself, and taking some other `Account` for it only keeps a diagnostic.
            (argument as? TypeScriptSingleType)
                ?.let { PsiTreeUtil.getChildOfType(it, JSReferenceExpression::class.java) }
                ?.referenceName == name
        } == true

/**
 * What [reference] names, looking through import and re-export aliases to the declarations themselves.
 *
 * The IDE does not resolve every module specifier `@effect/tsgo` does (a `package.json` `exports`
 * wildcard that maps every subpath to a source file, or a package importing itself by name). What the
 * reference then ends at depends on the IDE: its import specifier, or, when the TypeScript service
 * answers in place of the index (`typescript.service.based.fallback.resolve`, 2026.3), whatever that
 * service navigates to. So when no class or constant comes out of it, the class is found by name from
 * the import statement itself ([importedClasses]).
 */
private fun declarationsOf(reference: JSReferenceExpression): List<PsiElement> {
    val resolved = resolvedDeclarations(reference)
    if (resolved.any { it is JSClass || it is JSVariable }) {
        return resolved
    }
    return importedClasses(reference).ifEmpty { resolved }
}

private fun resolvedDeclarations(reference: JSReferenceExpression): List<PsiElement> {
    val declarations = ArrayList<PsiElement>()
    val seen = HashSet<PsiElement>()
    val pending = ArrayDeque(reference.multiResolve(false).mapNotNull { it.element })
    while (pending.isNotEmpty() && seen.size < MAX_RESOLVED_ELEMENTS) {
        val element = pending.removeFirst()
        if (!seen.add(element)) {
            continue
        }
        when (element) {
            // multiResolve, not findReferencedElements: the latter stops at the `export default` statement.
            is ES6ImportedBinding ->
                if (element.isNamespaceImport) {
                    declarations += element
                } else {
                    pending += element.multiResolve(false).mapNotNull { it.element }
                }
            is ES6ImportExportSpecifierAlias ->
                (element.findAliasedElement() ?: element.findSpecifierElement())?.let(pending::add) ?: declarations.add(element)
            is ES6ImportExportSpecifier -> {
                val targets = element.multiResolve(false).mapNotNull { it.element }.filter { it != element }
                when {
                    targets.isNotEmpty() -> pending += targets
                    element.isDefault -> declarations += element
                    // A re-export from a module the IDE cannot resolve, in whichever file the chain reached.
                    else -> indexedClasses(element.referenceName, element.declaration?.fromClause, element)
                        .ifEmpty { listOf(element) }
                        .let(declarations::addAll)
                }
            }
            // The TypeScript service navigates to the name of a declaration, not to the declaration.
            else -> declarations += (element.parent as? JSClass)?.takeIf { it.nameIdentifier == element } ?: element
        }
    }
    return declarations
}

/**
 * The classes [reference] can mean according to the import statement that binds its name in its own
 * file: `import { A } from "..."`, `import { A as B } from "..."`, or `import * as Ns from "..."` for
 * `Ns.A`. A default import does not say what the class is called.
 */
private fun importedClasses(reference: JSReferenceExpression): List<JSClass> {
    val qualifier = reference.qualifier
    val boundName = when (qualifier) {
        null -> reference.referenceName
        is JSReferenceExpression -> qualifier.referenceName.takeIf { qualifier.qualifier == null }
        else -> null
    } ?: return emptyList()
    for (declaration in PsiTreeUtil.findChildrenOfType(reference.containingFile, ES6ImportDeclaration::class.java)) {
        val fromClause = declaration.fromClause ?: continue
        if (qualifier != null) {
            if (declaration.importedBindings.any { it.isNamespaceImport && it.name == boundName }) {
                return indexedClasses(reference.referenceName, fromClause, reference)
            }
        } else {
            val specifier = declaration.importSpecifiers.firstOrNull { !it.isDefault && (it.alias?.name ?: it.referenceName) == boundName }
            if (specifier != null) {
                return indexedClasses(specifier.referenceName, fromClause, reference)
            }
        }
    }
    return emptyList()
}

/**
 * The classes called [name] that an unresolved `from "<specifier>"` can mean: those declared in a file
 * named like the specifier's last segment (`"@proto/kernel/NotAMember.rejection"` and
 * `NotAMember.rejection.ts`), or, when the specifier names no such file (a package entry point), every
 * class of that name. The caller requires all of them to agree before it drops a diagnostic.
 */
private fun indexedClasses(name: String?, fromClause: ES6FromClause?, context: PsiElement): List<JSClass> {
    if (name.isNullOrEmpty()) {
        return emptyList()
    }
    val project = context.project
    val classes = JSClassIndex.getElements(name, project, GlobalSearchScope.allScope(project))
        .filterIsInstance<JSClass>()
        .filter { !it.isInterface && it.name == name }
    val module = fromClause?.referenceText?.let(::moduleBaseName)
    val inNamedFile = classes.filter { it.containingFile?.name?.let(::moduleBaseName) == module }
    return inNamedFile.ifEmpty { classes }
}

/** `"@proto/kernel/NotAMember.rejection"`, `'./NotAMember.rejection.ts'` and `NotAMember.rejection.d.ts` all give `NotAMember.rejection`. */
private fun moduleBaseName(specifierOrFileName: String): String =
    specifierOrFileName.trim('"', '\'', '`').substringAfterLast('/').replace(moduleExtension, "")

private val moduleExtension = Regex("""(\.d)?\.[cm]?[jt]sx?$""")

private fun List<EffectSchemaClassSelf>.combined(): EffectSchemaClassSelf = when {
    isEmpty() -> EffectSchemaClassSelf.UNKNOWN
    any { it == EffectSchemaClassSelf.DECLARED } -> EffectSchemaClassSelf.DECLARED
    all { it == EffectSchemaClassSelf.ABSENT } -> EffectSchemaClassSelf.ABSENT
    else -> EffectSchemaClassSelf.UNKNOWN
}

private fun JSExpression.withoutParentheses(): JSExpression? {
    var current: JSExpression? = this
    while (current is JSParenthesizedExpression) {
        current = current.innerExpression
    }
    return current
}

/** Bounds alias chains: a re-export cycle must not keep the highlighting pass busy. */
private const val MAX_RESOLVED_ELEMENTS = 32

/** How many `const B = A` steps are followed from a class's base to the factory call. */
private const val MAX_BASE_ALIASES = 4

/** How many calls deep a factory result is looked for among call arguments. */
private const val MAX_WRAPPED_CALLS = 4
