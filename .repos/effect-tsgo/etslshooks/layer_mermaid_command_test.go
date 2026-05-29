package etslshooks

import (
	"testing"

	"github.com/microsoft/typescript-go/shim/lsp/lsproto"
)

func TestParseLayerMermaidRequestDefaultsToFullGraph(t *testing.T) {
	args := []any{map[string]any{
		"path":      "/tmp/app.ts",
		"line":      4,
		"character": 8,
	}}
	params := &lsproto.ExecuteCommandParams{
		Command:   layerMermaidCommand,
		Arguments: &args,
	}

	request, err := parseLayerMermaidRequest(params)
	if err != nil {
		t.Fatalf("parseLayerMermaidRequest returned error: %v", err)
	}

	if request.Path != "/tmp/app.ts" {
		t.Fatalf("request.Path = %q, want /tmp/app.ts", request.Path)
	}
	if request.Kind != "full" {
		t.Fatalf("request.Kind = %q, want full", request.Kind)
	}
}

func TestParseLayerMermaidRequestRejectsInvalidKind(t *testing.T) {
	args := []any{map[string]any{
		"path":      "/tmp/app.ts",
		"line":      4,
		"character": 8,
		"kind":      "sideways",
	}}
	params := &lsproto.ExecuteCommandParams{
		Command:   layerMermaidCommand,
		Arguments: &args,
	}

	if _, err := parseLayerMermaidRequest(params); err == nil {
		t.Fatal("parseLayerMermaidRequest returned nil error for invalid kind")
	}
}

func TestLayerMermaidPathAndURIAcceptsFileURI(t *testing.T) {
	fileName, uri := layerMermaidPathAndURI("file:///tmp/app.ts")

	if fileName != "/tmp/app.ts" {
		t.Fatalf("fileName = %q, want /tmp/app.ts", fileName)
	}
	if string(uri) != "file:///tmp/app.ts" {
		t.Fatalf("uri = %q, want file:///tmp/app.ts", uri)
	}
}
