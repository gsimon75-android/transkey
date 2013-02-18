package org.dyndns.fules.transkey;
import org.dyndns.fules.transkey.R;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceActivity;
import android.util.Log;
import android.preference.Preference;
import android.preference.PreferenceScreen;

public class TransparentKeyboardSettings extends PreferenceActivity implements SharedPreferences.OnSharedPreferenceChangeListener {
	private static final String	TAG = "TransparentKeyboard";

	@Override protected void
	onCreate(Bundle b) {
		super.onCreate(b);

		getPreferenceManager().setSharedPreferencesName(TransparentKeyboard.SHARED_PREFS_NAME);
		addPreferencesFromResource(R.xml.transkey_settings);
		SharedPreferences prefs = getPreferenceManager().getSharedPreferences();
		prefs.registerOnSharedPreferenceChangeListener(this);
	}

	@Override protected void
	onDestroy() {
		getPreferenceManager().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
		super.onDestroy();
	}

	public void
	onSharedPreferenceChanged(SharedPreferences prefs, String key) {
	}
}

// vim: set ai si sw=8 ts=8 noet:
