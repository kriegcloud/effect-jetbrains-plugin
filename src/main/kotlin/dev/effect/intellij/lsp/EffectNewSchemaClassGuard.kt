package dev.effect.intellij.lsp

import com.intellij.lang.ecmascript6.psi.ES6FromClause
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
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import org.eclipse.lsp4j.Diagnostic

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

    /** The declaration could not be read (indexing, unresolved reference, no class declaration). */
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

/** Classifies the class constructed by the `new` expression whose callee covers [calleeRange]. */
internal fun constructedSchemaClassSelf(file: PsiFile, calleeRange: TextRange): EffectSchemaClassSelf {
    val project = file.project
    if (DumbService.isDumb(project)) {
        return EffectSchemaClassSelf.UNKNOWN
    }
    // Diagnostic offsets belong to the document; they only address the PSI once it is committed.
    val documents = PsiDocumentManager.getInstance(project)
    if (documents.getCachedDocument(file)?.let(documents::isCommitted) == false) {
        return EffectSchemaClassSelf.UNKNOWN
    }
    val callee = constructedExpression(file, calleeRange) as? JSReferenceExpression
        ?: return EffectSchemaClassSelf.UNKNOWN
    return try {
        // A class merged with a namespace or an interface resolves to all of them; only the class decides.
        declarationsOf(callee).filterIsInstance<JSClass>().filterNot { it.isInterface }.map(::selfOf).combined()
    } catch (_: IndexNotReadyException) {
        EffectSchemaClassSelf.UNKNOWN
    }
}

private fun constructedExpression(file: PsiFile, calleeRange: TextRange): JSExpression? {
    if (calleeRange.startOffset !in 0 until file.textLength) {
        return null
    }
    val newExpression = PsiTreeUtil.getParentOfType(file.findElementAt(calleeRange.startOffset), JSNewExpression::class.java)
    val callee = newExpression?.methodExpression ?: return null
    return callee.takeIf { it.textRange.contains(calleeRange) }?.withoutParentheses()
}

private fun selfOf(schemaClass: JSClass): EffectSchemaClassSelf {
    val name = schemaClass.name ?: return EffectSchemaClassSelf.UNKNOWN
    val heritage = schemaClass.extendsList?.members?.singleOrNull() ?: return EffectSchemaClassSelf.UNKNOWN
    if (heritage.hasTypeArgument(name)) {
        return EffectSchemaClassSelf.DECLARED
    }
    val base = heritage.expression?.withoutParentheses() ?: return EffectSchemaClassSelf.UNKNOWN
    return when {
        factorySpine(base).any { it.hasTypeArgument(name) } -> EffectSchemaClassSelf.DECLARED
        base is JSCallExpression -> EffectSchemaClassSelf.ABSENT
        base is JSReferenceExpression -> declarationsOf(base).mapNotNull { selfOfBase(it, name) }.combined()
        else -> EffectSchemaClassSelf.UNKNOWN
    }
}

/**
 * A class that extends a bare reference gets its `make` from what the reference names: another class
 * (whose `make` returns that class), or a constant holding the factory result. Declaration files always
 * take the second form: `declare const A_base: Schema.Class<A, ...>; class A extends A_base {}`.
 * `null` for a declaration that says nothing about the base (a namespace or interface merged with it).
 */
private fun selfOfBase(base: PsiElement, name: String): EffectSchemaClassSelf? = when (base) {
    is JSClass -> EffectSchemaClassSelf.ABSENT.takeUnless { base.isInterface }
    is JSVariable -> {
        val declaredType = (base as? TypeScriptVariable)?.typeElement
        val initializer = base.initializer
        when {
            declaredType != null && declaredType.namesAsTypeArgument(name) -> EffectSchemaClassSelf.DECLARED
            initializer != null && factorySpine(initializer).any { it.hasTypeArgument(name) } -> EffectSchemaClassSelf.DECLARED
            declaredType == null && initializer == null -> EffectSchemaClassSelf.UNKNOWN
            else -> EffectSchemaClassSelf.ABSENT
        }
    }
    else -> null
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

/** `Schema.Class<A, ...>`, alone or as a member of an intersection or union. */
private fun PsiElement.namesAsTypeArgument(name: String): Boolean =
    hasTypeArgument(name) || children.any { it is TypeScriptSingleType && it.hasTypeArgument(name) }

private fun PsiElement.hasTypeArgument(name: String): Boolean =
    PsiTreeUtil.getChildOfType(this, TypeScriptTypeArgumentList::class.java)
        ?.typeArguments
        ?.any { argument ->
            val reference = (argument as? TypeScriptSingleType)?.referenceExpression
            reference != null && reference.qualifier == null && reference.referenceName == name
        } == true

/**
 * What [reference] names, looking through import and re-export aliases to the declarations themselves.
 *
 * The IDE does not resolve every module specifier `@effect/tsgo` does (a `package.json` `exports`
 * wildcard that maps every subpath to a source file, or a package importing itself by name), and the
 * reference then ends at its import specifier. [indexedClasses] finds the class by name in that case.
 */
private fun declarationsOf(reference: JSReferenceExpression): List<PsiElement> {
    val declarations = ArrayList<PsiElement>()
    val seen = HashSet<PsiElement>()
    val pending = ArrayDeque(reference.multiResolve(false).mapNotNull { it.element })
    if (pending.isEmpty()) {
        pending += memberOfUnresolvedNamespaceImport(reference)
    }
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
                    else -> indexedClasses(element.referenceName, element.declaration?.fromClause, element)
                        .ifEmpty { listOf(element) }
                        .let(declarations::addAll)
                }
            }
            else -> declarations += element
        }
    }
    return declarations
}

/** `Model.NotAMember`, where `import * as Model from "..."` names a module the IDE cannot resolve. */
private fun memberOfUnresolvedNamespaceImport(reference: JSReferenceExpression): List<PsiElement> {
    val namespace = (reference.qualifier as? JSReferenceExpression)
        ?.multiResolve(false)
        ?.mapNotNull { it.element as? ES6ImportedBinding }
        ?.singleOrNull { it.isNamespaceImport }
        ?: return emptyList()
    val fromClause = namespace.declaration?.fromClause ?: return emptyList()
    if (fromClause.resolveReferencedElements().isNotEmpty()) {
        return emptyList()
    }
    return indexedClasses(reference.referenceName, fromClause, reference)
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
