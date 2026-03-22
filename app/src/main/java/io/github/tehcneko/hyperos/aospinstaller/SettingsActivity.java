package io.github.tehcneko.hyperos.aospinstaller;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.PreferenceFragment;

import androidx.annotation.Nullable;

import io.github.libxposed.service.XposedService;

public class SettingsActivity extends Activity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        setContentView(R.layout.settiings);
        if (savedInstanceState == null) {
            getFragmentManager().beginTransaction()
                    .add(R.id.fragment_container, new SettingsFragment()).commit();
        }
    }

    public static class SettingsFragment extends PreferenceFragment implements App.ServiceStateListener {
        ListPreference unlockPref;
        EditTextPreference customNamePref;
        private XposedService mService;

        private void applyServiceStateToPrefs(XposedService service) {
            this.mService = service;
            if (unlockPref == null || customNamePref == null) {
                return;
            }

            if (service == null) {
                unlockPref.setEnabled(false);
                customNamePref.setEnabled(false);
                return;
            }

            var remotePrefs = service.getRemotePreferences("conf");
            unlockPref.setValue(remotePrefs.getString("package_installer_unlock", "off"));
            unlockPref.setEnabled(true);
            customNamePref.setText(remotePrefs.getString("package_installer_custom_package_name", ""));
            customNamePref.setEnabled("custom".equals(unlockPref.getValue()));
        }

        private void pushRemoteConfig(String changedKey, String newValue) {
            if (mService == null) return;

            var remotePrefs = mService.getRemotePreferences("conf");

            remotePrefs.edit()
                    .putString(changedKey, newValue)
                    .apply();
        }

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            addPreferencesFromResource(R.xml.preferences);
            unlockPref = (ListPreference) findPreference("package_installer_unlock");
            customNamePref = (EditTextPreference) findPreference("package_installer_custom_package_name");
            if (unlockPref != null) {
                unlockPref.setOnPreferenceChangeListener((preference, newValue) -> {
                    String value = String.valueOf(newValue).trim();
                    customNamePref.setEnabled("custom".equals(value));
                    pushRemoteConfig("package_installer_unlock", value);
                    return true;
                });

            }
            if (customNamePref != null) {
                customNamePref.setOnPreferenceChangeListener((preference, newValue) -> {
                    String value = String.valueOf(newValue).trim();
                    pushRemoteConfig("package_installer_custom_package_name", value);
                    return true;
                });
            }
        }

        @Override
        public void onStart() {
            super.onStart();
            App.addServiceStateListener(this);
        }

        @Override
        public void onStop() {
            App.removeServiceStateListener(this);
            super.onStop();
        }

        @Override
        public void onServiceStateChanged(@Nullable XposedService service) {
            applyServiceStateToPrefs(service);
        }
    }
}
