import * as Y from "yjs";
import { describe, expect, it } from "vitest";
import { createEmptyDocument, addLayer } from "@/features/editor/model/document";
import { createCollaborativeDocument, patchEditorDocument, readEditorDocument } from "@/features/editor/collaboration/document";
import type { EditorDocument } from "@/features/editor/model/types";

function baseDocument(): EditorDocument {
  return createEmptyDocument(800, 600);
}

describe("collaborative editor document", () => {
  it("merges independent field changes without replacing the other field", () => {
    const base = baseDocument();
    const source = createCollaborativeDocument(base);
    const left = new Y.Doc();
    const right = new Y.Doc();
    Y.applyUpdate(left, Y.encodeStateAsUpdate(source));
    Y.applyUpdate(right, Y.encodeStateAsUpdate(source));

    const leftDocument = readEditorDocument(left);
    const rightDocument = readEditorDocument(right);
    leftDocument.transform.rotation = 90;
    rightDocument.adjustments.brightness = 25;
    patchEditorDocument(left, leftDocument, "document.transform");
    patchEditorDocument(right, rightDocument, "adjustment.set");

    Y.applyUpdate(left, Y.encodeStateAsUpdate(right), "remote");
    Y.applyUpdate(right, Y.encodeStateAsUpdate(left), "remote");
    const merged = readEditorDocument(left);
    expect(merged.transform.rotation).toBe(90);
    expect(merged.adjustments.brightness).toBe(25);
  });

  it("keeps concurrent text additions in the shared Y.Text", () => {
    const text = {
      id: "text-1", type: "text" as const, text: "标题", left: 10, top: 10,
      fontSize: 32, width: 200, color: "#ffffff", fontFamily: "sans-serif", fontWeight: "600",
      angle: 0, scaleX: 1, scaleY: 1, flipX: false, flipY: false,
    };
    const source = createCollaborativeDocument(addLayer(baseDocument(), text));
    const left = new Y.Doc();
    const right = new Y.Doc();
    const update = Y.encodeStateAsUpdate(source);
    Y.applyUpdate(left, update);
    Y.applyUpdate(right, update);
    const leftDocument = readEditorDocument(left);
    const rightDocument = readEditorDocument(right);
    leftDocument.layers[0] = { ...leftDocument.layers[0], text: "标题 A" } as typeof leftDocument.layers[0];
    rightDocument.layers[0] = { ...rightDocument.layers[0], text: "标题 B" } as typeof rightDocument.layers[0];
    patchEditorDocument(left, leftDocument, "layer.patch");
    patchEditorDocument(right, rightDocument, "layer.patch");
    Y.applyUpdate(left, Y.encodeStateAsUpdate(right), "remote");
    const mergedText = readEditorDocument(left).layers.find((layer) => layer.id === "text-1");
    expect(mergedText?.type).toBe("text");
    if (mergedText?.type === "text") expect(mergedText.text).toContain("标题");
  });

  it("appends stroke chunks to Y.Array instead of rewriting the existing path", () => {
    const drawing = {
      id: "stroke-1", type: "drawing" as const, tool: "pen" as const, color: "#3370ff",
      size: 4, opacity: 1, path: [["M", 1, 1], ["L", 2, 2]] as [string, ...number[]][],
      left: 0, top: 0, scaleX: 1, scaleY: 1, flipX: false, flipY: false, angle: 0,
    };
    const doc = createCollaborativeDocument(addLayer(baseDocument(), drawing));
    const next = readEditorDocument(doc);
    next.layers[0] = { ...next.layers[0], path: [...drawing.path, ["L", 3, 3]] } as typeof next.layers[0];
    patchEditorDocument(doc, next, "teacup-stroke-preview");
    const layer = doc.getMap<Y.Map<unknown>>("layers").get("stroke-1") as Y.Map<unknown>;
    expect((layer.get("path") as Y.Array<unknown>).length).toBe(3);
    expect(readEditorDocument(doc).layers[0]).toMatchObject({ id: "stroke-1", type: "drawing" });
  });
});
