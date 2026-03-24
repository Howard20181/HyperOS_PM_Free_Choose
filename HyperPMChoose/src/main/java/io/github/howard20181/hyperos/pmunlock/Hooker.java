package io.github.howard20181.hyperos.pmunlock;

import static io.github.howard20181.hyperos.pmunlock.App.PACKAGE_MIME_TYPE;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.github.libxposed.api.XposedModule;

@SuppressLint("PrivateApi")
public class Hooker extends XposedModule {

    private static final String TAG = "HyperInstaller";

    @Override
    public void onSystemServerStarting(@NonNull SystemServerStartingParam param) {
        var classLoader = param.getClassLoader();
        try {
            hookPackageManagerServiceImpl(classLoader);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to hook PackageManagerServiceImpl", t);
        }
    }

    private void hookPackageManagerServiceImpl(ClassLoader classLoader) throws ClassNotFoundException {
        AtomicBoolean fakeCTS = new AtomicBoolean(false);
        var packageManagerServiceImpl = classLoader.loadClass("com.android.server.pm.PackageManagerServiceImpl");
        var methods = packageManagerServiceImpl.getDeclaredMethods();
        for (var method : methods) {
            var name = method.getName();
            if ("hookChooseBestActivity".equals(name) ||
                    "updateDefaultPkgInstallerLocked".equals(name) ||
                    "assertValidApkAndInstaller".equals(name)) {
                Log.d(TAG, "hooking method " + name);
                hook(method).intercept(chain -> {
                    fakeCTS.set(true);
                    try {
                        return chain.proceed();
                    } finally {
                        fakeCTS.set(false);
                    }
                });
                if ("hookChooseBestActivity".equals(name)) {
                    var prefs = getRemotePreferences("conf");
                    var replacePackageInstaller = new AtomicReference<>(prefs.getString("package_installer_unlock", "off"));
                    var customPackageInstallerName = new AtomicReference<>(prefs.getString("package_installer_custom_package_name", "").trim());
                    var mCurrentPackageInstaller = new AtomicReference<>("");
                    var unlockMarket = new AtomicReference<>(prefs.getBoolean("unlock_choose_market_app", false));
                    Field fCurrentPackageInstaller = null;
                    try {
                        fCurrentPackageInstaller = packageManagerServiceImpl.getDeclaredField("mCurrentPackageInstaller");
                        fCurrentPackageInstaller.setAccessible(true);
                    } catch (Exception e) {
                        log(Log.ERROR, TAG, "Failed to find mCurrentPackageInstaller field", e);
                    }
                    prefs.registerOnSharedPreferenceChangeListener((sharedPreferences, key) -> {
                        if ("package_installer_unlock".equals(key)) {
                            replacePackageInstaller.set(sharedPreferences.getString(key, "off"));
                        } else if ("package_installer_custom_package_name".equals(key)) {
                            customPackageInstallerName.set(sharedPreferences.getString(key, "").trim());
                        } else if ("unlock_choose_market_app".equals(key)) {
                            unlockMarket.set(sharedPreferences.getBoolean(key, false));
                        }
                    });
                    Field finalFCurrentPackageInstaller = fCurrentPackageInstaller;
                    hook(method).intercept(chain -> {
                        try {
                            if (chain.getArg(0) instanceof Intent intent)
                                if (finalFCurrentPackageInstaller != null
                                        && PACKAGE_MIME_TYPE.equals(intent.getType())
                                        && Intent.ACTION_VIEW.equals(intent.getAction())) {
                                    switch (replacePackageInstaller.get()) {
                                        case "any":
                                            if (chain.getArg(5) instanceof ResolveInfo ri) {
                                                return ri;
                                            } else {
                                                for (var arg : chain.getArgs()) {
                                                    if (arg instanceof ResolveInfo ri) {
                                                        return ri;
                                                    }
                                                }
                                            }
                                            break;
                                        case "custom":
                                            if (!customPackageInstallerName.get().isEmpty()) {
                                                var thisObject = chain.getThisObject();
                                                if (mCurrentPackageInstaller.get().isEmpty()
                                                        && finalFCurrentPackageInstaller.get(thisObject) instanceof String currentPackageInstaller) {
                                                    mCurrentPackageInstaller.compareAndSet("", currentPackageInstaller);
                                                }
                                                finalFCurrentPackageInstaller.set(thisObject, customPackageInstallerName.get());
                                            }
                                            break;
                                    }
                                } else if (unlockMarket.get()) {
                                    String scheme = intent.getScheme();
                                    String host = intent.getData() != null ? intent.getData().getHost() : null;
                                    if (scheme != null && ((scheme.equals("mimarket")
                                            || scheme.equals("market"))
                                            && Intent.ACTION_VIEW.equals(intent.getAction())
                                            && host != null && (host.equals("details")
                                            || host.equals("search")))) {
                                        var uri = intent.getData();
                                        var uriBuilder = uri.buildUpon()
                                                .scheme("market");
                                        intent.setData(uriBuilder.build());
                                        if (chain.getArg(5) instanceof ResolveInfo ri) {
                                            return ri;
                                        }
                                    }
                                }
                            return chain.proceed();
                        } finally {
                            if ("custom".equals(replacePackageInstaller.get())
                                    && !mCurrentPackageInstaller.get().isEmpty()
                                    && chain.getArg(0) instanceof Intent intent) {
                                if (finalFCurrentPackageInstaller != null
                                        && PACKAGE_MIME_TYPE.equals(intent.getType())
                                        && Intent.ACTION_VIEW.equals(intent.getAction())) {
                                    finalFCurrentPackageInstaller.set(chain.getThisObject(), mCurrentPackageInstaller.get());
                                }
                            }
                        }
                    });
                }
                deoptimize(method);
            }
        }
        try {
            var isCTSMethod = packageManagerServiceImpl.getDeclaredMethod("isCTS");
            hook(isCTSMethod).intercept(chain -> {
                if (fakeCTS.get()) {
                    return true;
                }
                return chain.proceed();
            });
        } catch (Exception e) {
            log(Log.ERROR, TAG, "Failed to hook isCTS", e);
        }
    }
}
