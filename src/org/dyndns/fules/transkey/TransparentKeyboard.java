package org.dyndns.fules.transkey;
import org.dyndns.fules.transkey.R;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.res.XmlResourceParser;
import android.inputmethodservice.InputMethodService;
import android.inputmethodservice.KeyboardView.OnKeyboardActionListener;
import android.inputmethodservice.KeyboardView;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.R.id;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import android.inputmethodservice.AbstractInputMethodService;
import android.view.ViewGroup;
import android.view.ViewParent;

public class TransparentKeyboard extends InputMethodService implements KeyboardView.OnKeyboardActionListener, SharedPreferences.OnSharedPreferenceChangeListener  {
	public static final String	SHARED_PREFS_NAME = "TransparentKeyboardSettings";
	public static final int[]	builtinLayouts = { R.xml.default_latin }; // keep in sync with constants.xml
	private static final String	TAG = "TransparentKeyboard";

	int				width, defKeyWidth;
	private SharedPreferences	mPrefs;					// the preferences instance
	TransparentKeyboardView		v;					// the current view
	int				currentLayout;
	boolean				forcePortrait;				// use the portrait layout even for horizontal screens

	ExtractedTextRequest		etreq = new ExtractedTextRequest();
	int				selectionStart = -1, selectionEnd = -1;

	// send an auto-revoked notification with a title and a message
	void sendNotification(String title, String msg) {
		// Simple as a pie, isn't it...
		Notification n = new Notification(android.R.drawable.ic_notification_clear_all, title, System.currentTimeMillis());
		n.flags = Notification.FLAG_AUTO_CANCEL;
		n.setLatestEventInfo(this, title, msg, PendingIntent.getActivity(this, 0, new Intent(), 0));
		((NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE)).notify(1, n);
		Log.e(TAG, title+"; "+msg);
	}

	// Update the layout if needed
	public String updateLayout(int id) {
		String name = null;
		String err = null;

		if ((id < 0) || (id == currentLayout))
			return "same";

		Log.d(TAG, "Loading layout '"+String.valueOf(id)+"'");
		if (id == 0)
			id = R.xml.default_latin;
		try {
			name = v.readLayout(getResources().getXml(id));
			currentLayout = id;
		}
		catch (XmlPullParserException e) {
			Log.e(TAG, "Cannot parse layout; err='" + e.getMessage() + "'");
		}
		catch (IOException e) {
			Log.e(TAG, "Cannot read layout resource;");
		}
		return name;
	}

	@Override public AbstractInputMethodService.AbstractInputMethodImpl onCreateInputMethodInterface() {
		mPrefs = getSharedPreferences(SHARED_PREFS_NAME, 0);
		etreq.hintMaxChars = etreq.hintMaxLines = 0;

		v = new TransparentKeyboardView(this);
		v.setOnKeyboardActionListener(this);

		currentLayout = -1;
		updateLayout(R.xml.default_latin);

		mPrefs.registerOnSharedPreferenceChangeListener(this);
		return super.onCreateInputMethodInterface();
	}

	// Select the layout view appropriate for the screen direction, if there is more than one
	@Override public View onCreateInputView() {
		DisplayMetrics metrics = getResources().getDisplayMetrics();

		Log.v(TAG, "w=" + String.valueOf(metrics.widthPixels) + ", h=" + String.valueOf(metrics.heightPixels) + ", forceP=" + String.valueOf(forcePortrait));
		if (v != null)
			v.calculateSizesForMetrics(metrics);
		else
			Log.e(TAG, "onCreateInputView: v is null");
	
		ViewParent p = v.getParent();
		if ((p != null) && (p instanceof ViewGroup))
			((ViewGroup)p).removeView(v);
		return v;
	} 

	@Override public void onStartInputView(EditorInfo attribute, boolean restarting) {
		super.onStartInputView(attribute, restarting);
		if (v != null) {
			v.setInputType(attribute.inputType);
		}
	}

	@Override public void onStartInput(EditorInfo attribute, boolean restarting) {
		super.onStartInput(attribute, restarting); 
		if (v != null) {
			v.setInputType(attribute.inputType);
		}
	}

	@Override public boolean onEvaluateFullscreenMode() {
		return false; // never require fullscreen
	}

	private void sendModifiers(InputConnection ic, int action) {
		if (v == null)
			return;

		/*if (v.checkState("shift"))
			ic.sendKeyEvent(new KeyEvent(action, KeyEvent.KEYCODE_SHIFT_LEFT));
		if (v.checkState("alt"))
			ic.sendKeyEvent(new KeyEvent(action, KeyEvent.KEYCODE_ALT_LEFT));
		if (v.checkState("altgr"))
			ic.sendKeyEvent(new KeyEvent(action, KeyEvent.KEYCODE_ALT_RIGHT));*/
	}

	// Process a generated keycode
	public void onKey(int primaryCode, int[] keyCodes) {
		InputConnection ic = getCurrentInputConnection();
		sendModifiers(ic, KeyEvent.ACTION_DOWN);
		sendDownUpKeyEvents(primaryCode);
		sendModifiers(ic, KeyEvent.ACTION_UP);
	}

	// Process the generated text
	public void onText(CharSequence text) {
		InputConnection ic = getCurrentInputConnection();
		sendModifiers(ic, KeyEvent.ACTION_DOWN);
		sendKeyChar(text.charAt(0));
	} 

	// Process a command
	public void execCmd(String cmd) {
		InputConnection ic = getCurrentInputConnection();

		if (cmd.equals("selectStart")) {
			selectionStart = ic.getExtractedText(etreq, 0).selectionStart;
			if ((selectionStart >= 0) && (selectionEnd >= 0)) {
				//Log.v(TAG, "selection " + String.valueOf(selectionStart) + ".." + String.valueOf(selectionEnd));
				ic.setSelection(selectionStart, selectionEnd);
				selectionStart = selectionEnd = -1;
			}
		}
		else if (cmd.equals("selectEnd")) {
			selectionEnd = ic.getExtractedText(etreq, 0).selectionEnd;
			if ((selectionStart >= 0) && (selectionEnd >= 0)) {
				//Log.v(TAG, "selection " + String.valueOf(selectionStart) + ".." + String.valueOf(selectionEnd));
				ic.setSelection(selectionStart, selectionEnd);
				selectionStart = selectionEnd = -1;
			}
		}
		else if (cmd.equals("selectAll"))
			ic.performContextMenuAction(android.R.id.selectAll);
		else if (cmd.equals("copy"))
			ic.performContextMenuAction(android.R.id.copy);
		else if (cmd.equals("cut"))
			ic.performContextMenuAction(android.R.id.cut);
		else if (cmd.equals("paste"))
			ic.performContextMenuAction(android.R.id.paste);
		else if (cmd.equals("switchIM"))
			ic.performContextMenuAction(android.R.id.switchInputMethod);
		else
			Log.w(TAG, "Unknown cmd '" + cmd + "'");
	}

	public void pickDefaultCandidate() {
	}

	public void swipeRight() {
	}

	public void swipeLeft() {
	}

	public void swipeDown() {
		requestHideSelf(0);
	}

	public void swipeUp() {
	}

	public void onPress(int primaryCode) {
	}

	public void onRelease(int primaryCode) {
	} 

	public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
		Log.d(TAG, "Changing pref "+key+" to "+prefs.getString(key, ""));
	}
}

// vim: set ai si sw=8 ts=8 noet:
