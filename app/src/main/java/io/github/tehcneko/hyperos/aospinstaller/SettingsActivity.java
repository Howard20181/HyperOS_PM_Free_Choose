package io.github.tehcneko.hyperos.aospinstaller;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceFragment;

import androidx.annotation.Nullable;

public class SettingsActivity extends Activity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settiings);
        if (savedInstanceState == null) {
            getFragmentManager().beginTransaction()
                    .add(R.id.fragment_container, new SettingsFragment()).commit();
        }
    }

    public static class SettingsFragment extends PreferenceFragment implements SharedPreferences.OnSharedPreferenceChangeListener {
        private void processServiceBind() {
            var unlockPref = findPreference("package_installer_unlock");
            var customNamePref = findPreference("package_installer_custom_package_name");
            if (App.mService != null) {
                var remotePrefs = App.mService.getRemotePreferences("conf");
                var localPrefs = getPreferenceManager().getSharedPreferences();
                SharedPreferences.Editor editor = localPrefs.edit();
                for (String key : remotePrefs.getAll().keySet()) {
                    Object value = remotePrefs.getAll().get(key);
                    if (value instanceof String) {
                        editor.putString(key, (String) value);
                    }
                }
                editor.apply();
                if (unlockPref != null) {
                    unlockPref.setEnabled(true);
                }
                if ("custom".equals(remotePrefs.getString("package_installer_unlock", "off"))) {
                    if (customNamePref != null) {
                        customNamePref.setEnabled(true);
                    }
                }
            } else {
                if (unlockPref != null) {
                    unlockPref.setEnabled(false);
                }
                if (customNamePref != null) {
                    customNamePref.setEnabled(false);
                }
            }
        }

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            addPreferencesFromResource(R.xml.preferences);
        }

        @Override
        public void onResume() {
            super.onResume();
            getPreferenceManager().getSharedPreferences()
                    .registerOnSharedPreferenceChangeListener(this);
            processServiceBind();
        }

        @Override
        public void onPause() {
            super.onPause();
            getPreferenceManager().getSharedPreferences()
                    .unregisterOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, @Nullable String key) {
            if (App.mService != null && key != null) {
                var perf = App.mService.getRemotePreferences("conf");
                if ("package_installer_unlock".equals(key)) {
                    var customNamePref = findPreference("package_installer_custom_package_name");
                    if (customNamePref != null) {
                        customNamePref.setEnabled("custom".equals(sharedPreferences.getString(key, "off")));
                    }
                }
                String value = sharedPreferences.getString(key, "");
                if ("package_installer_custom_package_name".equals(key)) {
                    value = value.trim();
                    sharedPreferences.edit().putString(key, value).apply();
                }
                perf.edit().putString(key, value).apply();
            }
        }
    }
}
