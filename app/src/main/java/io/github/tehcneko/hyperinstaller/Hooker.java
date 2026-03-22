package io.github.tehcneko.hyperinstaller;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;

import io.github.libxposed.api.XposedModule;

@SuppressLint("PrivateApi")
public class Hooker extends XposedModule {

    private static final String TAG = "HyperInstaller";
    private static final String PACKAGE_MIME_TYPE = "application/vnd.android.package-archive";
    public static boolean fakeCTS = false;
    private static Field fCurrentPackageInstaller;
    private static String mCurrentPackageInstaller;
    private static String replacePackageInstaller = "off";
    private static String customPackageInstallerName = "";

    @Override
    public void onSystemServerStarting(@NonNull SystemServerStartingParam param) {
        var classLoader = param.getClassLoader();
        try {
            var PackageManagerServiceImplClass = classLoader.loadClass("com.android.server.pm.PackageManagerServiceImpl");
            fCurrentPackageInstaller = PackageManagerServiceImplClass.getDeclaredField("mCurrentPackageInstaller");
            fCurrentPackageInstaller.setAccessible(true);
        } catch (ClassNotFoundException | NoSuchFieldException e) {
            log(Log.ERROR, TAG, "Failed to find IS_INTERNATIONAL_BUILD field", e);
        }
        try {
            hookPackageManagerServiceImpl(classLoader);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to hook PackageManagerServiceImpl", t);
        }
        try {
            hookIsCTS(classLoader);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to hook isCTS", t);
        }
    }

    private void hookPackageManagerServiceImpl(ClassLoader classLoader) throws ClassNotFoundException {
        var packageManagerServiceImpl = classLoader.loadClass("com.android.server.pm.PackageManagerServiceImpl");
        var methods = packageManagerServiceImpl.getDeclaredMethods();
        for (var method : methods) {
            var name = method.getName();
            if ("hookChooseBestActivity".equals(name) ||
                    "updateDefaultPkgInstallerLocked".equals(name) ||
                    "assertValidApkAndInstaller".equals(name)) {
                Log.d(TAG, "hooking method " + name);
                hook(method).intercept(chain -> {
                    fakeCTS = true;
                    try {
                        return chain.proceed();
                    } finally {
                        fakeCTS = false;
                    }
                });
                if ("hookChooseBestActivity".equals(name)) {
                    var prefs = getRemotePreferences("conf");
                    replacePackageInstaller = prefs.getString("package_installer_unlock", "off");
                    customPackageInstallerName = prefs.getString("package_installer_custom_package_name", "").trim();
                    prefs.registerOnSharedPreferenceChangeListener((sharedPreferences, key) -> {
                        if ("package_installer_unlock".equals(key)) {
                            replacePackageInstaller = sharedPreferences.getString(key, "off");
                        } else if ("package_installer_custom_package_name".equals(key)) {
                            customPackageInstallerName = sharedPreferences.getString(key, "").trim();
                        }
                    });
                    hook(method).intercept(chain -> {

                        var args = chain.getArgs();
                        if (args.get(0) instanceof Intent intent)
                            if (PACKAGE_MIME_TYPE.equals(intent.getType()) && "android.intent.action.VIEW".equals(intent.getAction())) {
                                switch (replacePackageInstaller) {
                                    case "any":
                                        if (args.get(5) instanceof ResolveInfo ri) {
                                            return ri;
                                        } else {
                                            for (var arg : args) {
                                                if (arg instanceof ResolveInfo ri) {
                                                    return ri;
                                                }
                                            }
                                        }
                                        break;
                                    case "custom":
                                        if (!customPackageInstallerName.isEmpty()) {
                                            var thisObject = chain.getThisObject();
                                            mCurrentPackageInstaller = (String) fCurrentPackageInstaller.get(thisObject);
                                            fCurrentPackageInstaller.set(thisObject, customPackageInstallerName);
                                        }
                                        break;
                                }
                            } else {
                                String scheme = intent.getScheme();
                                String host = intent.getData() != null ? intent.getData().getHost() : null;
                                if (scheme != null && ((scheme.equals("mimarket") || scheme.equals("market")) && "android.intent.action.VIEW".equals(intent.getAction()) && host != null && (host.equals("details") || host.equals("search")))) {
                                    var uri = intent.getData();
                                    var uriBuilder = uri.buildUpon()
                                            .scheme("market");
                                    intent.setData(uriBuilder.build());
                                    if (args.get(5) instanceof ResolveInfo ri) {
                                        return ri;
                                    }
                                }
                            }
                        try {
                            return chain.proceed();
                        } finally {
                            if ("custom".equals(replacePackageInstaller) && chain.getArg(0) instanceof Intent intent) {
                                if (PACKAGE_MIME_TYPE.equals(intent.getType()) && "android.intent.action.VIEW".equals(intent.getAction())) {
                                    var thisObject = chain.getThisObject();
                                    fCurrentPackageInstaller.set(thisObject, mCurrentPackageInstaller);
                                }
                            }
                        }
                    });
                }
                deoptimize(method);
            }
        }
    }

    private void hookIsCTS(ClassLoader classLoader) throws NoSuchMethodException, ClassNotFoundException {
        var packageManagerServiceImpl = classLoader.loadClass("com.android.server.pm.PackageManagerServiceImpl");
        var isCTSMethod = packageManagerServiceImpl.getDeclaredMethod("isCTS");
        hook(isCTSMethod).intercept(chain -> {
            if (fakeCTS) {
                return true;
            }
            return chain.proceed();
        });
    }
}
