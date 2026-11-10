package de.jarvis.app.wecker;

import de.jarvis.app.R;

import android.content.Context;
import android.text.InputType;
import android.view.ActionMode;
import android.view.DragEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.view.inputmethod.InputContentInfo;
import android.os.Bundle;
import android.widget.EditText;

/** Eingabefeld ohne Einfügen, Kopieren, Drag & Drop oder Tastatur-Zwischenablage. */
public class NoPasteEditText extends EditText {

    static final int MAX_CHUNK = 25;

    public NoPasteEditText(Context c) {
        super(c);
        setLongClickable(false);
        setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        ActionMode.Callback block = new ActionMode.Callback() {
            @Override public boolean onCreateActionMode(ActionMode m, Menu menu) { return false; }
            @Override public boolean onPrepareActionMode(ActionMode m, Menu menu) { return false; }
            @Override public boolean onActionItemClicked(ActionMode m, MenuItem item) { return false; }
            @Override public void onDestroyActionMode(ActionMode m) {}
        };
        setCustomSelectionActionModeCallback(block);
        setCustomInsertionActionModeCallback(block);
    }

    @Override
    public boolean onTextContextMenuItem(int id) {
        if (id == android.R.id.paste || id == android.R.id.pasteAsPlainText
                || id == android.R.id.copy || id == android.R.id.cut
                || id == android.R.id.autofill || id == android.R.id.replaceText) {
            return true; // geschluckt
        }
        return super.onTextContextMenuItem(id);
    }

    @Override
    public boolean isSuggestionsEnabled() { return false; }

    @Override
    public boolean onDragEvent(DragEvent event) { return false; }

    @Override
    public int getAutofillType() { return AUTOFILL_TYPE_NONE; }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        InputConnection base = super.onCreateInputConnection(outAttrs);
        if (base == null) return null;
        outAttrs.contentMimeTypes = null;
        return new InputConnectionWrapper(base, true) {
            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                if (text != null && text.length() > MAX_CHUNK) return false;
                return super.commitText(text, newCursorPosition);
            }

            @Override
            public boolean setComposingText(CharSequence text, int newCursorPosition) {
                if (text != null && text.length() > MAX_CHUNK) return false;
                return super.setComposingText(text, newCursorPosition);
            }

            @Override
            public boolean performContextMenuAction(int id) {
                if (id == android.R.id.paste || id == android.R.id.pasteAsPlainText) return false;
                return super.performContextMenuAction(id);
            }

            @Override
            public boolean commitContent(InputContentInfo info, int flags, Bundle opts) {
                return false;
            }
        };
    }
}
