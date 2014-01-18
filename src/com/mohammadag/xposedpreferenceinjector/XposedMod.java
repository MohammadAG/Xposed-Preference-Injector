package com.mohammadag.xposedpreferenceinjector;

import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.findClass;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.preference.PreferenceActivity;
import android.preference.PreferenceActivity.Header;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class XposedMod implements IXposedHookLoadPackage {

	private static final String SETTINGS_CATEGORY = "de.robv.android.xposed.category.MODULE_SETTINGS";
	private static Header mXposedHeader = null;

	@Override
	public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
		if (!lpparam.packageName.equals("com.android.settings"))
			return;

		Class<?> SettingsClazz = findClass("com.android.settings.Settings", lpparam.classLoader);
		final Class<?> HeaderAdapter = findClass("com.android.settings.Settings$HeaderAdapter", lpparam.classLoader);
		XposedBridge.hookAllMethods(SettingsClazz, "updateHeaderList", new XC_MethodHook() {
			@SuppressWarnings("unchecked")
			@Override
			protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
				List<Header> headers = (List<Header>) param.args[0];
				mXposedHeader= new Header();
				String mXposedModulesTitle = getResources().getString(R.string.xposed_modules);
				mXposedHeader.title = mXposedModulesTitle;
				headers.add(mXposedHeader);

				PreferenceActivity activity = (PreferenceActivity) param.thisObject;
				PackageManager pm = activity.getPackageManager();

				/* This will slow down Settings app loading till we async-it!!! */
				ArrayList<ApplicationInfo> xposedModulesList = new ArrayList<ApplicationInfo>();

				for (PackageInfo pkg : activity.getPackageManager().getInstalledPackages(PackageManager.GET_META_DATA)) {
					ApplicationInfo app = pkg.applicationInfo;
					if (!app.enabled)
						continue;

					if (app.metaData != null && app.metaData.containsKey("xposedmodule")) {
						xposedModulesList.add(app);
					}
				}

				Collections.sort(xposedModulesList, new ApplicationInfo.DisplayNameComparator(pm)); 

				for (ApplicationInfo info : xposedModulesList) {
					Intent intent = getSettingsIntent(pm, info.packageName);
					if (intent != null) {
						Header header = new Header();
						header.title = info.loadLabel(pm);
						header.iconRes = android.R.drawable.sym_def_app_icon;
						header.intent = intent;

						Bundle extras = new Bundle();
						extras.putString("xposed_package_name", info.packageName);
						extras.putBoolean("xposed_module", true);	
						header.extras = extras;

						headers.add(header);
					}
				}

				param.args[0] = headers;
			}
		});

		/* We can only pass resIds above, so we override the getView method to load the icon manually */
		findAndHookMethod(HeaderAdapter, "getView", int.class, View.class, ViewGroup.class, new XC_MethodHook() {
			@Override
			protected void afterHookedMethod(MethodHookParam param) throws Throwable {
				Header header = (Header) XposedHelpers.callMethod(param.thisObject, "getItem", param.args[0]);
				if (header.extras != null) {
					boolean isXposedModule = header.extras.getBoolean("xposed_module", false);
					if (isXposedModule) {
						String packageName = header.extras.getString("xposed_package_name");
						View view = (View) param.getResult();
						ImageView icon = (ImageView) XposedHelpers.getObjectField(view.getTag(), "icon");
						icon.setImageDrawable(view.getContext().getPackageManager().getApplicationIcon(packageName));
					}
				}
			}
		});
	}

	/* Taken from Xposed Installer */
	private static Intent getSettingsIntent(PackageManager pm, String packageName) {
		// taken from ApplicationPackageManager.getLaunchIntentForPackage(String)
		// first looks for an Xposed-specific category, falls back to getLaunchIntentForPackage

		Intent intentToResolve = new Intent(Intent.ACTION_MAIN);
		intentToResolve.addCategory(SETTINGS_CATEGORY);
		intentToResolve.setPackage(packageName);
		List<ResolveInfo> ris = pm.queryIntentActivities(intentToResolve, 0);

		if (ris == null || ris.size() <= 0) {
			return pm.getLaunchIntentForPackage(packageName);
		}

		Intent intent = new Intent(intentToResolve);
		intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		intent.setClassName(ris.get(0).activityInfo.packageName, ris.get(0).activityInfo.name);
		return intent;
	}

}
