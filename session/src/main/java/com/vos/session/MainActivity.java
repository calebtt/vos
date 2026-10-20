package com.vos.session;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The session's window. The chat window lands here later in M1 (decision 21); for now it shows the
 * build and takes the voice vendor's key by paste (§5.4). The window is FLAG_SECURE, so the key
 * field never appears in screenshots or the recents thumbnail. Vault work runs on the app-wide key
 * executor, and the status always comes from the vault after the last operation.
 */
public final class MainActivity extends Activity {
    private TextView keyStatus;
    private TextView keyNote;
    private EditText keyField;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(R.string.skeleton_notice);
        root.addView(title);

        keyStatus = new TextView(this);
        keyStatus.setPadding(0, pad, 0, pad / 2);
        root.addView(keyStatus);

        keyNote = new TextView(this);
        root.addView(keyNote);

        keyField = new EditText(this);
        keyField.setHint(R.string.key_hint);
        keyField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyField.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        keyField.setSingleLine(true);
        root.addView(keyField);

        Button save = new Button(this);
        save.setText(R.string.key_save);
        save.setOnClickListener(v -> saveKey());
        root.addView(save);

        Button remove = new Button(this);
        remove.setText(R.string.key_remove);
        remove.setOnClickListener(v -> app().keyWorker().execute(() -> {
            app().keyVault().delete(KeyVault.XAI);
            refresh(0);
        }));
        root.addView(remove);

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        app().keyWorker().execute(() -> {
            app().importPushedKeys();
            refresh(0);
        });
    }

    private void saveKey() {
        String value = keyField.getText().toString();
        if (value.trim().isEmpty()) {
            return;
        }
        keyField.getText().clear();
        app().keyWorker().execute(() -> {
            boolean failed = false;
            try {
                app().keyVault().put(KeyVault.XAI, value);
            } catch (Exception e) {
                failed = true;
            }
            boolean has = app().keyVault().has(KeyVault.XAI);
            refresh(!failed ? 0 : has ? R.string.key_rejected_kept : R.string.key_not_saved);
        });
    }

    /**
     * On the key executor: the status comes from the vault (whether a key is stored, never its
     * value); {@code note} is a message about the last attempt, or 0 for none.
     */
    private void refresh(int note) {
        boolean has = app().keyVault().has(KeyVault.XAI);
        runOnUiThread(() -> {
            keyStatus.setText(has ? R.string.key_present : R.string.key_absent);
            if (note == 0) {
                keyNote.setText("");
            } else {
                keyNote.setText(note);
            }
        });
    }

    private SessionApp app() {
        return (SessionApp) getApplication();
    }
}
