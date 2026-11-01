package com.vos.actuator;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * VOS's chat window (decision 21, Shell Contract row 25), owned by the actuator so that only the owner's
 * typing here (or in the chat notification's direct reply) opens a typed turn. The thread shows the
 * shared conversation's recent owner and VOS lines (voice turns included; reloaded from
 * {@code recentThread} every time the window resumes) and the replies to typed
 * turns. VOS's text is model output, shown as plain data under its own label, never styled like the
 * actuator's trusted cards. FLAG_SECURE: VOS's own windows are never captured or uploaded (§5.8).
 * It is exported as a launcher entry and reads no intent extras: launching it sends nothing.
 * While a typed turn is open, a Stop button beside Send stops it (P5, {@link TypedChat#stop}).
 */
public final class ChatActivity extends Activity implements TypedChat.Listener {
    private LinearLayout thread;
    private ScrollView scroll;
    private EditText input;
    private TextView status;
    private Button stopButton;
    private TypedChat chat;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        chat = ((ActuatorApp) getApplication()).typedChat();
        int pad = Math.round(12 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setFitsSystemWindows(true);
        scroll = new ScrollView(this);
        thread = new LinearLayout(this);
        thread.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(thread);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        status = new TextView(this);
        root.addView(status);
        LinearLayout row = new LinearLayout(this);
        input = new EditText(this);
        input.setHint(R.string.chat_hint);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setSingleLine(false);
        input.setMaxLines(4);
        input.setOnEditorActionListener((v, actionId, e) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                send();
                return true;
            }
            return false;
        });
        Button sendButton = new Button(this);
        sendButton.setText(R.string.chat_send);
        sendButton.setOnClickListener(v -> send());
        // Shown only while a typed turn is open. Its touches are not filtered when obscured, unlike
        // Send's: a stop is harmless to trick, and an overlay must not be able to block it (P5).
        stopButton = new Button(this);
        stopButton.setText(R.string.chat_stop);
        stopButton.setContentDescription(getString(R.string.chat_stop_description));
        stopButton.setOnClickListener(v -> chat.stop("window"));
        stopButton.setVisibility(View.GONE);
        row.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(stopButton);
        row.addView(sendButton);
        root.addView(row);
        // Another app's overlay must not be able to trick the owner into sending (tapjacking).
        input.setFilterTouchesWhenObscured(true);
        sendButton.setFilterTouchesWhenObscured(true);
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        chat.listen(this);
        ChatNotification.cancel(this);
        // Voice turns land in the shared conversation while this window is closed (decision 21).
        chat.loadThread(this);
        changed();
    }

    @Override
    protected void onPause() {
        chat.unlisten(this);
        super.onPause();
    }

    private void send() {
        String why = chat.send(input.getText().toString(), "window");
        if (why == null) {
            input.setText("");
        }
        status.setText(why == null ? "" : why);
    }

    @Override
    public void changed() {
        stopButton.setVisibility(chat.stoppable() ? View.VISIBLE : View.GONE);
        thread.removeAllViews();
        int pad = Math.round(8 * getResources().getDisplayMetrics().density);
        for (ChatThread.Line l : chat.lines()) {
            TextView label = new TextView(this);
            label.setTextSize(12);
            label.setTextColor(Color.GRAY);
            TextView body = new TextView(this);
            body.setTextIsSelectable(true);
            body.setPadding(0, 0, 0, pad);
            switch (l.role) {
                case "owner":
                    label.setText(R.string.chat_you);
                    label.setGravity(Gravity.END);
                    body.setGravity(Gravity.END);
                    break;
                case "vos":
                    label.setText(R.string.chat_model_label);
                    break;
                default:
                    label.setText("VOS actuator");
                    body.setTextColor(Color.GRAY);
                    break;
            }
            body.setText(l.text);
            thread.addView(label);
            thread.addView(body);
        }
        scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
    }
}
