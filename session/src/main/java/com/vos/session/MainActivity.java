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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The session's window. The chat window lands here later in M1 (decision 21); for now it shows the
 * build and takes the voice vendor's key by paste (§5.4). The window is FLAG_SECURE, so the key
 * field never appears in screenshots or the recents thumbnail.
 */
public final class MainActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView keyStatus;
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
        remove.setOnClickListener(v -> worker.execute(() -> {
            app().keyVault().delete(KeyVault.XAI);
            refresh();
        }));
        root.addView(remove);

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        worker.execute(() -> {
            app().importPushedKeys();
            refresh();
        });
    }

    @Override
    protected void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }

    private void saveKey() {
        String value = keyField.getText().toString();
        keyField.getText().clear();
        worker.execute(() -> {
            String error = null;
            try {
                app().keyVault().put(KeyVault.XAI, value);
            } catch (Exception e) {
                error = getString(R.string.key_not_saved);
            }
            String shown = error;
            runOnUiThread(() -> {
                if (shown != null) {
                    keyStatus.setText(shown);
                }
            });
            if (shown == null) {
                refresh();
            }
        });
    }

    /** Off the UI thread: reads only whether a key is stored, never its value. */
    private void refresh() {
        boolean has = app().keyVault().has(KeyVault.XAI);
        runOnUiThread(() -> keyStatus.setText(has ? R.string.key_present : R.string.key_absent));
    }

    private SessionApp app() {
        return (SessionApp) getApplication();
    }
}
