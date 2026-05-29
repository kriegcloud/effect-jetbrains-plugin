// Package etslshooks provides Effect code fix integration with TypeScript-Go.
// This package registers a single CodeFixProvider that delegates to internal/fixables.
//
// Import this package with a blank import in cmd/tsgo/main.go to register
// Effect code fix providers:
//
//	import _ "github.com/effect-ts/tsgo/etslshooks"
package etslshooks

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"

	"github.com/effect-ts/tsgo/etscore"
	"github.com/effect-ts/tsgo/internal/autoimportstyle"
	"github.com/effect-ts/tsgo/internal/completion"
	"github.com/effect-ts/tsgo/internal/completions"
	"github.com/effect-ts/tsgo/internal/fixable"
	"github.com/effect-ts/tsgo/internal/fixables"
	"github.com/effect-ts/tsgo/internal/layergraph"
	"github.com/effect-ts/tsgo/internal/pluginoptions"
	"github.com/effect-ts/tsgo/internal/refactor"
	"github.com/effect-ts/tsgo/internal/refactors"
	"github.com/effect-ts/tsgo/internal/typeparser"
	"github.com/microsoft/typescript-go/shim/ast"
	"github.com/microsoft/typescript-go/shim/astnav"
	"github.com/microsoft/typescript-go/shim/checker"
	"github.com/microsoft/typescript-go/shim/compiler"
	"github.com/microsoft/typescript-go/shim/core"
	"github.com/microsoft/typescript-go/shim/ls"
	"github.com/microsoft/typescript-go/shim/ls/autoimport"
	"github.com/microsoft/typescript-go/shim/ls/lsconv"
	"github.com/microsoft/typescript-go/shim/lsp/lsproto"
	"github.com/microsoft/typescript-go/shim/modulespecifiers"
)

const layerMermaidCommand = "_effectGetLayerMermaid"

type layerMermaidRequest struct {
	Path      string `json:"path"`
	Line      int    `json:"line"`
	Character int    `json:"character"`
	Kind      string `json:"kind,omitempty"`
}

type layerMermaidResponse struct {
	Success     bool   `json:"success"`
	MermaidCode string `json:"mermaidCode,omitempty"`
	Message     string `json:"message,omitempty"`
}

func init() {
	// Register the Effect code fix provider with the language service
	ls.RegisterCodeFixProvider(effectFixProvider)
	// Register the Effect refactor provider with the language service
	ls.RegisterRefactorProvider(effectRefactorProvider)
	// Register the Effect hover enrichment callback
	ls.RegisterAfterQuickInfoCallback(afterQuickInfo)
	// Register the Effect document symbol enrichment callback
	ls.RegisterAfterDocumentSymbolsCallback(afterDocumentSymbols)
	// Register the Effect inlay hints suppression callback
	ls.RegisterAfterInlayHintsCallback(afterInlayHints)
	// Register the Effect completion enrichment callback
	ls.RegisterAfterCompletionCallback(afterCompletion)
	// Register the Effect auto-import style transformer factory
	autoimport.RegisterAutoImportFixTransformer(func(_ modulespecifiers.UserPreferences, program *compiler.Program, importingFile *ast.SourceFile) autoimport.FixTransformer {
		var resolvedOptions *etscore.ResolvedEffectPluginOptions
		if effectConfig := program.Options().Effect; effectConfig != nil {
			resolvedOptions = pluginoptions.ResolveEffectPluginOptionsForSourceFile(
				effectConfig,
				importingFile.FileName(),
				program.Options().ConfigFilePath,
				program.UseCaseSensitiveFileNames(),
			)
		}
		return autoimportstyle.NewFixTransformer(resolvedOptions)
	})
	// Register the VS Code-compatible Mermaid command used by JetBrains and other LSP clients.
	ls.RegisterExecuteCommandHandler(layerMermaidCommand, ls.ExecuteCommandHandler{
		TextDocumentURI: layerMermaidTextDocumentURI,
		Execute:         executeLayerMermaidCommand,
	})
}

// effectFixProvider is the CodeFixProvider that handles all Effect diagnostic codes.
// It delegates to the fixables registered in internal/fixables.
var effectFixProvider = &ls.CodeFixProvider{
	ErrorCodes:     fixables.AllErrorCodes(),
	GetCodeActions: getEffectCodeActions,
	FixIds:         fixables.AllFixIDs(),
}

// getEffectCodeActions finds applicable fixables and collects their code actions.
func getEffectCodeActions(ctx context.Context, fixCtx *ls.CodeFixContext) ([]*ls.CodeAction, error) {
	// Find all fixables that handle this error code
	applicable := fixables.ByErrorCode(fixCtx.ErrorCode)
	if len(applicable) == 0 {
		return nil, nil
	}

	var options *etscore.ResolvedEffectPluginOptions
	if fixCtx.Program != nil {
		if parsedEffectConfig := fixCtx.Program.Options().Effect; parsedEffectConfig != nil {
			options = pluginoptions.ResolveEffectPluginOptionsForSourceFile(
				parsedEffectConfig,
				fixCtx.SourceFile.FileName(),
				fixCtx.Program.Options().ConfigFilePath,
				fixCtx.Program.UseCaseSensitiveFileNames(),
			)

			ch, done := fixCtx.Program.GetTypeCheckerForFile(ctx, fixCtx.SourceFile)
			defer done()

			if ch != nil {
				tp := typeparser.NewTypeParser(fixCtx.Program, ch)

				// Create the fixable context that wraps the code-fix request
				fCtx := fixable.NewContext(ctx, fixCtx, options, ch, tp)

				// Collect actions from all applicable fixables
				var actions []*ls.CodeAction
				for _, f := range applicable {
					results := f.Run(fCtx)
					for i := range results {
						action := results[i]
						actions = append(actions, &action)
					}
				}

				return actions, nil
			}
		}
	}

	return nil, nil
}

// effectRefactorProvider is the RefactorProvider that handles all Effect refactoring actions.
// It delegates to the refactors registered in internal/refactors.
var effectRefactorProvider = &ls.RefactorProvider{
	GetRefactorActions: getEffectRefactorActions,
}

// getEffectRefactorActions iterates all registered refactors and collects their code actions.
func getEffectRefactorActions(ctx context.Context, file *ast.SourceFile, span core.TextRange, program *compiler.Program, langService *ls.LanguageService) ([]ls.CodeAction, error) {
	if effectConfig := program.Options().Effect; effectConfig == nil || !effectConfig.GetRefactorsEnabled() {
		return nil, nil
	}

	ch, done := program.GetTypeCheckerForFile(ctx, file)
	defer done()
	tp := typeparser.NewTypeParser(program, ch)

	rCtx := refactor.NewContext(ctx, file, span, program, langService, ch, tp)

	var actions []ls.CodeAction
	for _, r := range refactors.All {
		results := r.Run(rCtx)
		actions = append(actions, results...)
	}

	return actions, nil
}

// afterCompletion is called after TypeScript-Go builds the completion list.
// It allows Effect to enrich completion responses with custom completions.
func afterCompletion(ctx context.Context, sf *ast.SourceFile, position int, items []*lsproto.CompletionItem, program *compiler.Program, langService *ls.LanguageService) []*lsproto.CompletionItem {
	effectConfig := program.Options().Effect
	if effectConfig == nil || !effectConfig.GetCompletionsEnabled() {
		return items
	}

	if len(completions.All) == 0 {
		return items
	}

	ch, done := program.GetTypeCheckerForFile(ctx, sf)
	defer done()
	tp := typeparser.NewTypeParser(program, ch)

	completionCtx := completion.NewContext(ctx, sf, position, items, program, langService, ch, tp)

	for _, c := range completions.All {
		results := c.Run(completionCtx)
		items = append(items, results...)
	}

	return items
}

// afterQuickInfo is called after building hover quickInfo and documentation.
// It allows Effect to enrich hover responses with Effect-specific information.
func afterQuickInfo(program checker.Program, c *checker.Checker, sf *ast.SourceFile, node *ast.Node, _ *ast.Symbol, quickInfo string, documentation string, isMarkdown bool) (string, string, *ast.Node) {
	tp := typeparser.NewTypeParser(program, c)

	// Check if Effect is enabled
	effectConfig := program.Options().Effect
	if effectConfig == nil || !effectConfig.GetQuickinfoEnabled() {
		return quickInfo, documentation, nil
	}

	// Yield* hover: detect yield keyword inside yield* expressions in Effect generator scopes
	if node.Kind == ast.KindYieldKeyword && node.Parent != nil && node.Parent.Kind == ast.KindYieldExpression {
		yield := node.Parent.AsYieldExpression()
		if yield.AsteriskToken != nil && yield.Expression != nil {
			if tp.GetEffectContextFlags(node)&typeparser.EffectContextFlagCanYieldEffect != 0 {
				t := tp.GetTypeAtLocation(yield.Expression)
				if t != nil {
					effect := tp.EffectYieldableType(t, yield.Expression)
					if effect != nil {
						typeStr := c.TypeToStringEx(t, nil, checker.TypeFormatFlagsNoTruncation, nil)
						quickInfo = "(yield*) " + typeStr
						documentation = formatEffectTypeParams(c, effect, "", isMarkdown)
						return quickInfo, documentation, node.Parent
					}
				}
			}
		}
	}

	// General symbol hover: enrich Effect-typed symbols with type parameters
	t := tp.GetTypeAtLocation(node)
	if t == nil {
		return quickInfo, documentation, nil
	}

	// Layer hover: detect Layer types and show providers/requirers summary.
	// Layer extends Effect in V4, so this check must come before the Effect check.
	// Only activate layer hover enrichment when the cursor is on the name of the declaration,
	// not on arbitrary nodes within the initializer expression.
	if tp.IsLayerType(t, node) && isDeclarationName(node) {
		documentation = formatLayerHover(tp, c, sf, node, t, documentation, isMarkdown, effectConfig)
		return quickInfo, documentation, nil
	}

	effect := tp.EffectType(t, node)
	if effect == nil {
		return quickInfo, documentation, nil
	}

	documentation = formatEffectTypeParams(c, effect, documentation, isMarkdown)

	return quickInfo, documentation, nil
}

func layerMermaidTextDocumentURI(params *lsproto.ExecuteCommandParams) (lsproto.DocumentUri, error) {
	request, err := parseLayerMermaidRequest(params)
	if err != nil {
		return "", err
	}
	_, uri := layerMermaidPathAndURI(request.Path)
	return uri, nil
}

func executeLayerMermaidCommand(ctx context.Context, languageService *ls.LanguageService, params *lsproto.ExecuteCommandParams) (lsproto.ExecuteCommandResponse, error) {
	request, err := parseLayerMermaidRequest(params)
	if err != nil {
		return layerMermaidResult(false, "", err.Error()), nil
	}

	fileName, _ := layerMermaidPathAndURI(request.Path)
	program := languageService.GetProgram()
	if program == nil {
		return layerMermaidResult(false, "", "No TypeScript program is available."), nil
	}

	sf := program.GetSourceFile(fileName)
	if sf == nil && fileName != request.Path {
		sf = program.GetSourceFile(request.Path)
	}
	if sf == nil {
		return layerMermaidResult(false, "", "Source file is not part of the active TypeScript program."), nil
	}

	effectConfig := program.Options().Effect
	if effectConfig == nil {
		return layerMermaidResult(false, "", "Effect compiler options are not enabled for this source file."), nil
	}

	position := int(ls.LanguageService_converters(languageService).LineAndCharacterToPosition(sf, lsproto.Position{
		Line:      uint32(request.Line),
		Character: uint32(request.Character),
	}))
	node := astnav.GetTouchingPropertyName(sf, position)
	if node == nil {
		return layerMermaidResult(false, "", "No Layer declaration found at the requested position."), nil
	}

	declarationName := findLayerDeclarationName(node)
	if declarationName == nil {
		return layerMermaidResult(false, "", "No Layer declaration found at the requested position."), nil
	}

	c, done := program.GetTypeCheckerForFile(ctx, sf)
	defer done()
	if c == nil {
		return layerMermaidResult(false, "", "Type checker is not available for this source file."), nil
	}

	tp := typeparser.NewTypeParser(program, c)
	layerType := tp.GetTypeAtLocation(declarationName)
	if layerType == nil || !tp.IsLayerType(layerType, declarationName) {
		return layerMermaidResult(false, "", "The requested declaration is not an Effect Layer."), nil
	}

	mermaidCode := buildLayerMermaidDiagram(tp, c, sf, declarationName, effectConfig, request.Kind)
	if mermaidCode == "" {
		return layerMermaidResult(false, "", "No Layer graph could be extracted for this declaration."), nil
	}

	return layerMermaidResult(true, mermaidCode, ""), nil
}

func parseLayerMermaidRequest(params *lsproto.ExecuteCommandParams) (layerMermaidRequest, error) {
	var request layerMermaidRequest
	if params == nil {
		return request, fmt.Errorf("missing execute command params")
	}
	if params.Command != layerMermaidCommand {
		return request, fmt.Errorf("unsupported execute command: %s", params.Command)
	}
	if params.Arguments == nil || len(*params.Arguments) == 0 {
		return request, fmt.Errorf("missing Layer Mermaid request argument")
	}

	payload, err := json.Marshal((*params.Arguments)[0])
	if err != nil {
		return request, fmt.Errorf("invalid Layer Mermaid request argument: %w", err)
	}
	if err := json.Unmarshal(payload, &request); err != nil {
		return request, fmt.Errorf("invalid Layer Mermaid request argument: %w", err)
	}

	request.Path = strings.TrimSpace(request.Path)
	if request.Path == "" {
		return request, fmt.Errorf("Layer Mermaid request path is required")
	}
	if request.Line < 0 || request.Character < 0 {
		return request, fmt.Errorf("Layer Mermaid request position must be non-negative")
	}
	switch request.Kind {
	case "", "full", "nested":
		request.Kind = "full"
	case "outline":
	default:
		return request, fmt.Errorf("unsupported Layer Mermaid graph kind: %s", request.Kind)
	}
	return request, nil
}

func layerMermaidPathAndURI(path string) (string, lsproto.DocumentUri) {
	if strings.HasPrefix(path, "file:") || strings.Contains(path, "://") {
		uri := lsproto.DocumentUri(path)
		return uri.FileName(), uri
	}
	return path, lsconv.FileNameToDocumentURI(path)
}

func layerMermaidResult(success bool, mermaidCode string, message string) lsproto.ExecuteCommandResponse {
	var body any = layerMermaidResponse{
		Success:     success,
		MermaidCode: mermaidCode,
		Message:     message,
	}
	return lsproto.ExecuteCommandResponse{LSPAny: &body}
}

func findLayerDeclarationName(node *ast.Node) *ast.Node {
	for current := node; current != nil; current = current.Parent {
		switch current.Kind {
		case ast.KindVariableDeclaration:
			return current.AsVariableDeclaration().Name()
		case ast.KindPropertyDeclaration:
			return current.AsPropertyDeclaration().Name()
		}
	}
	if isDeclarationName(node) {
		return node
	}
	return nil
}

func buildLayerMermaidDiagram(tp *typeparser.TypeParser, c *checker.Checker, sf *ast.SourceFile, node *ast.Node, effectConfig *etscore.EffectPluginOptions, kind string) string {
	var initializer *ast.Node
	if node.Parent != nil {
		switch node.Parent.Kind {
		case ast.KindVariableDeclaration:
			initializer = node.Parent.AsVariableDeclaration().Initializer
		case ast.KindPropertyDeclaration:
			initializer = node.Parent.AsPropertyDeclaration().Initializer
		}
	}
	if initializer == nil {
		return ""
	}

	opts := layergraph.ExtractLayerGraphOptions{
		FollowSymbolsDepth: effectConfig.GetLayerGraphFollowDepth(),
	}
	fullGraph := layergraph.ExtractLayerGraph(tp, c, initializer, sf, opts)
	if kind == "outline" {
		outlineGraph := layergraph.ExtractOutlineGraph(c, fullGraph)
		return layergraph.FormatOutlineGraph(c, outlineGraph, sf)
	}
	return layergraph.FormatNestedLayerGraph(c, fullGraph, sf)
}

// formatLayerHover builds the Layer hover documentation including providers/requirers
// summary, Mermaid diagram links, and Layer type parameters.
func formatLayerHover(tp *typeparser.TypeParser, c *checker.Checker, sf *ast.SourceFile, node *ast.Node, _ *checker.Type, documentation string, isMarkdown bool, effectConfig *etscore.EffectPluginOptions) string {
	// Try to resolve the initializer expression for layer graph extraction.
	var initializer *ast.Node
	if node.Parent != nil {
		switch node.Parent.Kind {
		case ast.KindVariableDeclaration:
			initializer = node.Parent.AsVariableDeclaration().Initializer
		case ast.KindPropertyDeclaration:
			initializer = node.Parent.AsPropertyDeclaration().Initializer
		}
	}

	var quickInfoSummary string
	var hasGraph bool
	var nestedDiagram, outlineDiagram string
	if initializer != nil {
		opts := layergraph.ExtractLayerGraphOptions{
			FollowSymbolsDepth: effectConfig.GetLayerGraphFollowDepth(),
		}
		fullGraph := layergraph.ExtractLayerGraph(tp, c, initializer, sf, opts)
		info := layergraph.ExtractProvidersAndRequirers(c, fullGraph)
		quickInfoSummary = layergraph.FormatQuickInfo(c, info, sf)
		hasGraph = true

		if !effectConfig.NoExternal {
			nestedDiagram = layergraph.FormatNestedLayerGraph(c, fullGraph, sf)
			outlineGraph := layergraph.ExtractOutlineGraph(c, fullGraph)
			outlineDiagram = layergraph.FormatOutlineGraph(c, outlineGraph, sf)
		}
	}

	// Build combined documentation: quickinfo summary (provides/requires) and links.
	var b strings.Builder

	if quickInfoSummary != "" {
		if isMarkdown {
			b.WriteString("```\n")
			b.WriteString(quickInfoSummary)
			b.WriteString("\n```\n")
		} else {
			b.WriteString(quickInfoSummary)
			b.WriteString("\n")
		}
	}

	// Generate Mermaid diagram links when we have a graph and external links are not suppressed.
	if hasGraph && !effectConfig.NoExternal {
		baseURL := effectConfig.GetMermaidBaseURL()

		var nestedURL, outlineURL string
		if nestedDiagram != "" {
			nestedURL = layergraph.EncodeMermaidURL(baseURL, nestedDiagram)
		}
		if outlineDiagram != "" {
			outlineURL = layergraph.EncodeMermaidURL(baseURL, outlineDiagram)
		}

		if isMarkdown {
			switch {
			case nestedURL != "" && outlineURL != "":
				fmt.Fprintf(&b, "[Show full graph](%s) - [Show outline](%s)\n\n", nestedURL, outlineURL)
			case nestedURL != "":
				fmt.Fprintf(&b, "[Show full graph](%s)\n\n", nestedURL)
			case outlineURL != "":
				fmt.Fprintf(&b, "[Show outline](%s)\n\n", outlineURL)
			}
		} else {
			if nestedURL != "" {
				fmt.Fprintf(&b, "{@link %s Show full graph}\n\n", nestedURL)
			}
			if outlineURL != "" {
				fmt.Fprintf(&b, "{@link %s Show outline}\n\n", outlineURL)
			}
		}
	}

	if documentation != "" {
		b.WriteString("\n")
		b.WriteString(documentation)
	}

	return b.String()
}

// formatLayerTypeParams formats Layer type parameters (Provides, Error, Requires).
func formatLayerTypeParams(c *checker.Checker, layer *typeparser.Layer, isMarkdown bool) string {
	rOutStr := c.TypeToStringEx(layer.ROut, nil, checker.TypeFormatFlagsNoTruncation, nil)
	eStr := c.TypeToStringEx(layer.E, nil, checker.TypeFormatFlagsNoTruncation, nil)
	rInStr := c.TypeToStringEx(layer.RIn, nil, checker.TypeFormatFlagsNoTruncation, nil)

	if isMarkdown {
		return fmt.Sprintf("```ts\n/* Layer Type Parameters */\ntype Provides = %s\ntype Error = %s\ntype Requires = %s\n```\n", rOutStr, eStr, rInStr)
	}
	return fmt.Sprintf("Layer Type Parameters:\n  Provides = %s\n  Error = %s\n  Requires = %s\n", rOutStr, eStr, rInStr)
}

// isDeclarationName checks whether the given node is the name node of a variable or property declaration.
// This is used to restrict layer hover enrichment to the declaration name only,
// not to arbitrary nodes within the initializer expression.
func isDeclarationName(node *ast.Node) bool {
	if node.Parent == nil {
		return false
	}
	switch node.Parent.Kind {
	case ast.KindVariableDeclaration:
		return node.Parent.AsVariableDeclaration().Name() == node
	case ast.KindPropertyDeclaration:
		return node.Parent.AsPropertyDeclaration().Name() == node
	}
	return false
}

// formatEffectTypeParams formats Effect type parameters (A, E, R) and prepends them to documentation.
func formatEffectTypeParams(c *checker.Checker, effect *typeparser.Effect, documentation string, isMarkdown bool) string {
	aStr := c.TypeToStringEx(effect.A, nil, checker.TypeFormatFlagsNoTruncation, nil)
	eStr := c.TypeToStringEx(effect.E, nil, checker.TypeFormatFlagsNoTruncation, nil)
	rStr := c.TypeToStringEx(effect.R, nil, checker.TypeFormatFlagsNoTruncation, nil)

	var prefix string
	if isMarkdown {
		prefix = fmt.Sprintf("```ts\n/* Effect Type Parameters */\ntype Success = %s\ntype Failure = %s\ntype Requirements = %s\n```\n", aStr, eStr, rStr)
	} else {
		prefix = fmt.Sprintf("Effect Type Parameters:\n  Success = %s\n  Failure = %s\n  Requirements = %s\n", aStr, eStr, rStr)
	}

	var b strings.Builder
	b.WriteString(prefix)
	if documentation != "" {
		b.WriteString("\n")
		b.WriteString(documentation)
	}
	return b.String()
}
