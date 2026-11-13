package dev.effect.intellij.lsp

import com.intellij.codeInsight.daemon.impl.AnnotationHolderImpl
import com.intellij.codeInsight.daemon.impl.AnnotationSessionImpl
import com.intellij.lang.javascript.psi.stubs.JSClassIndex
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.effect.intellij.lsp.fixall.withoutUnsatisfiableDiagnostics
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.messages.Either

/**
 * `@effect/tsgo` reports `newSchemaClass` for every constructable Schema type with a `make` property.
 * The fixtures reproduce the declaration shapes that decide whether `make` returns what `new` returns:
 * `schema.ts` stands in for `effect/Schema` (the class is passed as `Self`), `domain.ts` for a factory
 * that fills `Self` itself, as `@alchemy.run/domain` does.
 */
class EffectNewSchemaClassGuardTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "schema.ts",
            """
            export interface Codec<T> { readonly Type: T }
            type SchemaClass<Self> = {
              new (props?: object): Self
              make(props?: object): Self
              extend<Extended>(identifier: string): (fields: object) => SchemaClass<Extended>
            }
            export declare const Class: <Self, Brand = {}>(identifier: string) => (fields: object) => SchemaClass<Self>
            export declare const TaggedErrorClass: <Self>() => (tag: string, fields: object) => SchemaClass<Self>
            export declare const suspend: <T>(thunk: () => T) => T
            export declare const brand: <C>(schemaClass: C) => C
            export type { SchemaClass }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "domain.ts",
            """
            import type { SchemaClass } from "./schema"
            export type Instance<Tag extends string> = { readonly _tag: Tag }
            export interface RejectionClass<Tag extends string> extends SchemaClass<Instance<Tag>> { readonly kind: "Rejection" }
            export declare const Rejection: <const Tag extends string>(tag: Tag, fields?: object) => RejectionClass<Tag>
            export declare const Event: <const Tag extends string>(tag: Tag, fields?: object) => SchemaClass<Instance<Tag>>
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "model.ts",
            """
            import * as Domain from "./domain"
            import * as Schema from "./schema"

            export class NotAMember extends Domain.Rejection("NotAMember", {}) {}
            export class Opened extends Domain.Event("Opened", {}) {
              get shout(): string { return "OPENED" }
            }
            export class Tree extends Domain.Event("Tree", {
              children: Schema.suspend((): Schema.Codec<Tree> => Tree),
            }) {}
            export default class Unauthorized extends Domain.Rejection("Unauthorized") {}
            export const Anonymous = Domain.Rejection("Anonymous")

            export class Account extends Schema.Class<Account>("Account")({}) {}
            export class Branded extends Schema.Class<Branded, { readonly brand: unique symbol }>("Branded")({}) {}
            export class Denied extends Schema.TaggedErrorClass<Denied>()("Denied", {}) {}
            export class Savings extends Account.extend<Savings>("Savings")({}) {}
            export class Parenthesized extends (Schema.Class<Parenthesized>("Parenthesized")({})) {}
            export class Wrapped extends Schema.brand(Schema.Class<Wrapped>("Wrapped")({})) {}
            export class WrappedTwice extends Schema.brand((Schema.brand(Schema.Class<WrappedTwice>("WrappedTwice")({})))) {}
            export class WrappedRejection extends Schema.brand(Domain.Rejection("WrappedRejection")) {}
            const HeldWrappedBase = Schema.brand(Schema.Class<HeldWrapped>("HeldWrapped")({}))
            export class HeldWrapped extends HeldWrappedBase {}
            export class Subclass extends Account {}

            const HeldBase = Schema.Class<Held>("Held")({})
            export class Held extends HeldBase {}
            const HeldRejectionBase = Domain.Rejection("HeldRejection")
            export class HeldRejection extends HeldRejectionBase {}

            const AliasedBase = Schema.Class<Aliased>("Aliased")({})
            const AliasedBaseAlias = (AliasedBase)
            export class Aliased extends AliasedBaseAlias {}
            const AccountAlias = Account
            export class AliasedSubclass extends AccountAlias {}
            let Unassigned
            export class Undecided extends Unassigned {}

            export namespace Models {
              export class Scoped extends Schema.Class<Models.Scoped>("Scoped")({}) {}
            }

            export class Merged extends Domain.Event("Merged", {}) {}
            export declare namespace Merged {
              export type Encoded = { readonly _tag: "Merged" }
            }
            export class MergedAccount extends Schema.Class<MergedAccount>("MergedAccount")({}) {}
            export interface MergedAccount { readonly note: string }
            export declare namespace MergedAccount {
              export type Encoded = { readonly note: string }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject("late.ts", "import * as Domain from \"./domain\"\nclass Late extends Domain.Rejection(\"Late\") {}\nexport default Late\n")
        // Reachable only through specifiers the IDE cannot resolve: no `node_modules` entry, no `paths`.
        myFixture.addFileToProject(
            "packages/kernel/src/Sealed.rejection.ts",
            "import * as Domain from \"../../../domain\"\nexport class Sealed extends Domain.Rejection(\"Sealed\") {}\n",
        )
        myFixture.addFileToProject(
            "packages/kernel/src/Ledger.schema.ts",
            "import * as Schema from \"../../../schema\"\nexport class Posting extends Schema.Class<Posting>(\"Posting\")({}) {}\n",
        )
        myFixture.addFileToProject(
            "packages/kernel/src/Twin.rejection.ts",
            "import * as Domain from \"../../../domain\"\nexport class Twin extends Domain.Rejection(\"Twin\") {}\n",
        )
        myFixture.addFileToProject(
            "packages/ledger/src/Twin.schema.ts",
            "import * as Schema from \"../../../schema\"\nexport class Twin extends Schema.Class<Twin>(\"Twin\")({}) {}\n",
        )
        myFixture.addFileToProject(
            "published.d.ts",
            """
            import type * as Domain from "./domain"
            import type * as Schema from "./schema"

            declare const NotFound_base: Schema.SchemaClass<NotFound>
            export declare class NotFound extends NotFound_base {}
            declare const Mixed_base: Schema.SchemaClass<Mixed> & { readonly extra: true }
            export declare class Mixed extends Mixed_base {}
            declare const Refused_base: Domain.RejectionClass<"Refused">
            export declare class Refused extends Refused_base {}
            export declare namespace Errors {
              class Nested extends NotFound_base {}
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "barrel.ts",
            """
            export { NotAMember as Forbidden, Account as Ledger } from "./model"
            export * from "./model"
            """.trimIndent(),
        )
        myFixture.configureByText(
            "main.ts",
            """
            import Unauthorized, { Account, Anonymous, Branded, Denied, Held, HeldRejection, NotAMember, Opened } from "./model"
            import { Merged, MergedAccount, Parenthesized, Savings, Subclass, Tree, Wrapped } from "./model"
            import { Aliased, AliasedSubclass, Models, Undecided } from "./model"
            import { HeldWrapped, WrappedRejection, WrappedTwice } from "./model"
            import * as Model from "./model"
            import { Forbidden, Ledger, NotAMember as Refusal, Account as Book } from "./barrel"
            import { Errors, Mixed, NotFound, Refused } from "./published"
            import Late from "./late"
            import { Sealed, Sealed as Locked } from "@scope/kernel/Sealed.rejection"
            import * as Kernel from "@scope/kernel/Sealed.rejection"
            import { Posting } from "@scope/kernel/Ledger.schema"
            import { Twin as RejectedTwin } from "@scope/kernel/Twin.rejection"
            import { Twin as SchemaTwin } from "@scope/ledger/Twin.schema.ts"
            import { Twin as EntryPointTwin, Sealed as EntryPointSealed, Nowhere } from "@scope/everything"
            import Defaulted from "@scope/kernel/Sealed.rejection"

            class Local extends Model.Account {}
            class Plain {}
            const AnonymousClass = class Inline extends Model.Account {}

            export const constructed = [
              new NotAMember(), new Opened(), new Tree(), new Unauthorized(), new Model.NotAMember(),
              new Forbidden(), new Refusal(), new (NotAMember)(), new HeldRejection(), new Refused(),
              new Account(), new Branded(), new Denied(), new Savings(), new Parenthesized(), new Model.Account(),
              new Ledger(), new Book(), new Held(), new NotFound(), new Mixed(),
              new Subclass(), new Local(), new Errors.Nested(), new Wrapped(),
              new Anonymous(), new Missing(), new Plain(), new AnonymousClass(), new (class extends Model.Account {})(),
              new Late(), new Merged(), new MergedAccount(),
              new Aliased(), new AliasedSubclass(), new Undecided(), new Models.Scoped(),
              new WrappedTwice(), new WrappedRejection(), new HeldWrapped(),
              new Sealed(), new Locked(), new Kernel.Sealed(), new Posting(), new RejectedTwin(), new SchemaTwin(),
              new EntryPointTwin(), new EntryPointSealed(), new Nowhere(), new Defaulted(), new Kernel.Nowhere(),
            ]
            """.trimIndent(),
        )
    }

    fun testFactoryThatFillsSelfItselfIsAbsent() {
        assertSelf(EffectSchemaClassSelf.ABSENT, "NotAMember", "Opened", "Unauthorized", "HeldRejection", "Refused")
    }

    fun testFactoryThatFillsSelfItselfIsAbsentThroughImportShapes() {
        assertSelf(EffectSchemaClassSelf.ABSENT, "Model.NotAMember", "Forbidden", "Refusal", "(NotAMember)", "Late")
    }

    /** `export declare namespace X { export type Encoded = ... }` beside the class resolves to both. */
    fun testOnlyTheClassOfAMergedDeclarationDecides() {
        assertSelf(EffectSchemaClassSelf.ABSENT, "Merged")
        assertSelf(EffectSchemaClassSelf.DECLARED, "MergedAccount")
    }

    /**
     * `@effect/tsgo` resolves `exports` wildcards and package self-references that the IDE's own module
     * resolution does not; the class is then found by name, in the file the specifier ends with.
     */
    fun testClassBehindUnresolvedModuleSpecifierIsFoundByName() {
        assertSelf(EffectSchemaClassSelf.ABSENT, "Sealed", "Locked", "Kernel.Sealed", "RejectedTwin", "EntryPointSealed")
        assertSelf(EffectSchemaClassSelf.DECLARED, "Posting", "SchemaTwin")
    }

    /** Same-named classes that disagree, behind a specifier that names neither file: the diagnostic stays. */
    fun testAmbiguousOrMissingClassBehindUnresolvedModuleSpecifierIsNotDropped() {
        assertSelf(EffectSchemaClassSelf.DECLARED, "EntryPointTwin")
        assertSelf(EffectSchemaClassSelf.UNKNOWN, "Nowhere", "Defaulted", "Kernel.Nowhere")
    }

    fun testClassPassedAsSelfIsDeclared() {
        assertSelf(EffectSchemaClassSelf.DECLARED, "Account", "Branded", "Denied", "Savings", "Parenthesized")
    }

    fun testClassPassedAsSelfIsDeclaredThroughImportShapes() {
        assertSelf(EffectSchemaClassSelf.DECLARED, "Model.Account", "Ledger", "Book")
    }

    fun testConstantBaseCarriesSelfInItsInitializerOrDeclaredType() {
        assertSelf(EffectSchemaClassSelf.DECLARED, "Held", "NotFound", "Mixed")
    }

    /** `const B = A` keeps what `A` holds: the factory result with its `Self`, or another class. */
    fun testAliasOfConstantBaseIsFollowed() {
        assertSelf(EffectSchemaClassSelf.DECLARED, "Aliased")
        assertSelf(EffectSchemaClassSelf.ABSENT, "AliasedSubclass")
        assertSelf(EffectSchemaClassSelf.UNKNOWN, "Undecided")
    }

    /** Inside `namespace Models`, `Schema.Class<Models.Scoped>` names the class being declared. */
    fun testQualifiedSelfIsDeclared() {
        assertSelf(EffectSchemaClassSelf.DECLARED, "Models.Scoped")
    }

    /** `Subclass.make(...)` returns an `Account`; a class member added by the subclass is lost. */
    fun testPlainSubclassOfSchemaClassIsAbsent() {
        assertSelf(EffectSchemaClassSelf.ABSENT, "Subclass", "Local", "Errors.Nested")
    }

    /** A field that mentions its own class is not `Self`. */
    fun testOnlyTypeArgumentsOfTheFactoryCallsCount() {
        assertSelf(EffectSchemaClassSelf.ABSENT, "Tree")
    }

    /** Whether a call keeps the `make` of the factory result it is given cannot be read: not dropped. */
    fun testFactoryResultHandedToAnotherCallIsUnknown() {
        assertSelf(EffectSchemaClassSelf.UNKNOWN, "Wrapped", "WrappedTwice", "HeldWrapped")
        assertSelf(EffectSchemaClassSelf.ABSENT, "WrappedRejection")
    }

    fun testUndecidableDeclarationsStayUnknown() {
        assertSelf(EffectSchemaClassSelf.UNKNOWN, "Anonymous", "Missing", "Plain", "AnonymousClass", "(class extends Model.Account {})")
    }

    fun testRangeOutsideNewExpressionIsUnknown() {
        val file = myFixture.file
        val importOffset = file.text.indexOf("NotAMember,")
        assertEquals(EffectSchemaClassSelf.UNKNOWN, constructedSchemaClassSelf(file, TextRange(importOffset, importOffset + "NotAMember".length)))
        assertEquals(EffectSchemaClassSelf.UNKNOWN, constructedSchemaClassSelf(file, TextRange(file.textLength, file.textLength)))
        // A range wider than the callee belongs to some other expression.
        val callee = calleeRange("NotAMember")
        assertEquals(EffectSchemaClassSelf.UNKNOWN, constructedSchemaClassSelf(file, TextRange(callee.startOffset, callee.endOffset + 2)))
    }

    /**
     * The 2026.3 platform applies diagnostics when the server sends them, also during indexing, and
     * does not apply them again when indexing ends: the first answer has to be the right one.
     */
    fun testClassifiesWhileIndexing() {
        indexFixture()
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            assertSelf(EffectSchemaClassSelf.ABSENT, "NotAMember", "Forbidden", "Sealed", "Kernel.Sealed", "Subclass", "Refused")
            assertSelf(EffectSchemaClassSelf.DECLARED, "Account", "Posting", "NotFound", "Models.Scoped")
            assertSelf(EffectSchemaClassSelf.UNKNOWN, "Missing", "Anonymous")
            assertTrue(newSchemaClass("NotAMember").isUnsatisfiableNewSchemaClass(myFixture.file, calleeRange("NotAMember")))
            assertEmpty(annotationsFor(newSchemaClass("Sealed"), calleeRange("Sealed")))
        }
    }

    /** While indexing, a declaration that is not found may only be missing from the index: keep the last answer. */
    fun testAnswerIsRememberedWhileTheDeclarationCannotBeRead() {
        assertSelf(EffectSchemaClassSelf.ABSENT, "Sealed", "Locked")
        val declaration = myFixture.findFileInTempDir("packages/kernel/src/Sealed.rejection.ts")
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            WriteAction.runAndWait<RuntimeException> { declaration.delete(this) }
            assertSelf(EffectSchemaClassSelf.ABSENT, "Sealed", "Locked")
        }
        // Not indexing any more: the class is really gone.
        assertSelf(EffectSchemaClassSelf.UNKNOWN, "Sealed")
    }

    /** A file changed while the IDE was closed is not readable during the indexing run that follows. */
    fun testAsksAgainAfterIndexingForAnAnswerItNeverHad() {
        val reapplied = ArrayList<PsiFile>()
        val range = calleeRange("Sealed")
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            // The fixture files were never indexed: nothing reliable to read, nothing remembered.
            repeat(2) { assertEquals(EffectSchemaClassSelf.UNKNOWN, constructedSchemaClassSelf(myFixture.file, range, reapplied::add)) }
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertEmpty(reapplied)
        }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals(listOf(myFixture.file), reapplied)
        assertEquals(EffectSchemaClassSelf.ABSENT, constructedSchemaClassSelf(myFixture.file, range, reapplied::add))
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals(1, reapplied.size)
    }

    /** No Effect server runs in this fixture: asking for the diagnostics again must be harmless. */
    fun testReapplyWithoutARunningServer() {
        EffectLspDiagnosticsRefresh.reapply(myFixture.file)
    }

    fun testOnlyUnsatisfiableNewSchemaClassDiagnosticsAreDropped() {
        val file = myFixture.file
        assertTrue(newSchemaClass("NotAMember").isUnsatisfiableNewSchemaClass(file, calleeRange("NotAMember")))
        assertFalse(newSchemaClass("Account").isUnsatisfiableNewSchemaClass(file, calleeRange("Account")))
        assertFalse(newSchemaClass("Anonymous").isUnsatisfiableNewSchemaClass(file, calleeRange("Anonymous")))
        val otherRule = diagnostic("NotAMember", "Add a brand to this Schema class. effect(schemaClassMissingBrand)")
        assertFalse(otherRule.isUnsatisfiableNewSchemaClass(file, calleeRange("NotAMember")))
        val plainTypeScript = diagnostic("NotAMember", "Expected 1 arguments, but got 0.")
        assertFalse(plainTypeScript.isUnsatisfiableNewSchemaClass(file, calleeRange("NotAMember")))
    }

    fun testAnnotatorShowsOnlySatisfiableDiagnostics() {
        assertEmpty(annotationsFor(newSchemaClass("NotAMember"), calleeRange("NotAMember")))
        assertEmpty(annotationsFor(newSchemaClass("Subclass"), calleeRange("Subclass")))

        val shown = annotationsFor(newSchemaClass("Account"), calleeRange("Account")).single()
        assertEquals(calleeRange("Account"), TextRange(shown.startOffset, shown.endOffset))
        assertEquals(1, annotationsFor(newSchemaClass("Anonymous"), calleeRange("Anonymous")).size)
    }

    fun testFixAllSkipsUnsatisfiableSites() {
        val file = myFixture.file
        val unsatisfiable = newSchemaClass("NotAMember")
        val satisfiable = newSchemaClass("Account")
        val otherRule = diagnostic("Opened", "Add a brand to this Schema class. effect(schemaClassMissingBrand)")
        val outsideText = newSchemaClass("Account").also { it.range = Range(Position(10_000, 0), Position(10_000, 4)) }

        assertEquals(
            listOf(satisfiable, otherRule, outsideText),
            withoutUnsatisfiableDiagnostics(listOf(unsatisfiable, satisfiable, otherRule, outsideText), file, file.text),
        )
        val untouched = listOf(otherRule)
        assertSame(untouched, withoutUnsatisfiableDiagnostics(untouched, file, file.text))
    }

    /** As in an IDE whose indexes were built before the indexing run a test simulates. */
    private fun indexFixture() {
        JSClassIndex.getElements("Account", project, GlobalSearchScope.allScope(project))
    }

    private fun assertSelf(expected: EffectSchemaClassSelf, vararg callees: String) {
        callees.forEach { callee ->
            assertEquals("new $callee(...)", expected, constructedSchemaClassSelf(myFixture.file, calleeRange(callee)))
        }
    }

    /** The range `@effect/tsgo` reports: the expression between `new` and its argument list. */
    private fun calleeRange(callee: String): TextRange {
        val text = myFixture.file.text
        val start = text.indexOf("new $callee()").also { assertTrue("new $callee() is not in main.ts", it >= 0) } + "new ".length
        return TextRange(start, start + callee.length)
    }

    private fun newSchemaClass(callee: String): Diagnostic =
        diagnostic(
            callee,
            "This Schema class is constructed with `new`. `$callee.make(...)` can be used to construct the Schema class instance. effect(newSchemaClass)",
        ).also { it.code = Either.forRight(377094) }

    private fun diagnostic(callee: String, message: String): Diagnostic {
        val document = myFixture.getDocument(myFixture.file)
        val range = calleeRange(callee)
        fun position(offset: Int): Position {
            val line = document.getLineNumber(offset)
            return Position(line, offset - document.getLineStartOffset(line))
        }
        return Diagnostic(Range(position(range.startOffset), position(range.endOffset)), message).also { diagnostic ->
            diagnostic.severity = DiagnosticSeverity.Error
            diagnostic.source = "effect"
        }
    }

    private fun annotationsFor(diagnostic: Diagnostic, range: TextRange): List<com.intellij.lang.annotation.Annotation> {
        val file: PsiFile = myFixture.file
        val holder = AnnotationHolderImpl(AnnotationSessionImpl.create(file), false)
        holder.runAnnotatorWithContext(file)
        EffectLspDiagnosticsSupport().createAnnotation(holder, diagnostic, range, emptyList())
        return holder.toList()
    }
}
