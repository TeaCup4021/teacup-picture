import { describe, expect, it } from "vitest";
import { createEmptyDocument, setAdjustment } from "@/features/editor/model/document";
import { createEditorHistory, editorHistoryReducer } from "@/features/editor/model/history";

describe("editor history", () => {
  it("undoes and redoes a committed gesture", () => {
    const initial = createEmptyDocument(800, 600);
    const changed = setAdjustment(initial, "exposure", 50);
    const committed = editorHistoryReducer(createEditorHistory(initial), {
      type: "commit",
      document: changed,
    });

    const undone = editorHistoryReducer(committed, { type: "undo" });
    expect(undone.present.adjustments.exposure).toBe(0);
    expect(undone.future).toHaveLength(1);

    const redone = editorHistoryReducer(undone, { type: "redo" });
    expect(redone.present.adjustments.exposure).toBe(50);
    expect(redone.future).toHaveLength(0);
  });

  it("clears redo history after a new committed gesture", () => {
    const initial = createEmptyDocument(800, 600);
    const first = editorHistoryReducer(createEditorHistory(initial), {
      type: "commit",
      document: setAdjustment(initial, "exposure", 50),
    });
    const undone = editorHistoryReducer(first, { type: "undo" });
    const branched = editorHistoryReducer(undone, {
      type: "commit",
      document: setAdjustment(undone.present, "brightness", 25),
    });

    expect(branched.future).toEqual([]);
    expect(branched.present.adjustments).toMatchObject({ exposure: 0, brightness: 25 });
  });

  it("keeps at most fifty undo snapshots", () => {
    let history = createEditorHistory(createEmptyDocument(800, 600));
    for (let value = 1; value <= 70; value += 1) {
      history = editorHistoryReducer(history, {
        type: "commit",
        document: setAdjustment(history.present, "exposure", value),
      });
    }
    expect(history.past).toHaveLength(50);
  });
});
