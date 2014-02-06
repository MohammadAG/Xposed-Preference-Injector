package com.mohammadag.xposedpreferenceinjector;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.AsyncTask;
import android.os.FileObserver;
import android.preference.PreferenceActivity;
import android.preference.PreferenceActivity.Header;
import de.robv.android.xposed.XposedBridge;

public class ModuleLoader {
	@SuppressLint("SdCardPath")
	private static final String MODULES_LIST_FILE = "/data/data/de.robv.android.xposed.installer/conf/modules.list";
	private static final String SETTINGS_CATEGORY = "de.robv.android.xposed.category.MODULE_SETTINGS";

	private PreferenceActivity mActivity;
	private List<Header> mHeaders;
	private FileObserver mConfigObserver;
	private AsyncModuleTask mTask;
	private Object mLock = new Object();

	public ModuleLoader(PreferenceActivity activity, List<Header> headers) {
		mActivity = activity;
		mHeaders = headers;
		mConfigObserver = new ConfigObserver();
	}

	/* Taken from Xposed Installer */
	private static Intent getSettingsIntent(PackageManager pm, String packageName) {
		// taken from
		// ApplicationPackageManager.getLaunchIntentForPackage(String)
		// first looks for an Xposed-specific category, falls back to
		// getLaunchIntentForPackage

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

	private static List<String> getActiveModules() {
		List<String> modules = new ArrayList<String>();
		try {
			BufferedReader br = new BufferedReader(new FileReader(MODULES_LIST_FILE));

			String line;
			while ((line = br.readLine()) != null) {
				// modules.add(extractPackageName(line));
				modules.add(line);
			}
			br.close();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		return modules;
	}

	public void startObserver() {
		mConfigObserver.startWatching();
		XposedBridge.log("startObserver");
	}

	public void stopObserver() {
		mConfigObserver.stopWatching();
		XposedBridge.log("stopObserver");
	}

	public void updateModuleList() {
		XposedBridge.log("updateModuleList");
		stopTask();
		synchronized (mLock) {
			mTask = new AsyncModuleTask();
			mTask.execute();
		}
	}

	public void stopTask() {
		synchronized (mLock) {
			if (mTask != null)
				mTask.cancel(true);
		}
	}

	public class ConfigObserver extends FileObserver {

		public ConfigObserver() {
			super(MODULES_LIST_FILE, FileObserver.MODIFY);
		}

		@Override
		public void onEvent(int event, String path) {
			ModuleLoader.this.updateModuleList();
		}

	}

	public class AsyncModuleTask extends AsyncTask<Void, Void, List<Header>> {
		@Override
		protected List<Header> doInBackground(Void... params) {
			PackageManager pm = mActivity.getPackageManager();
			List<Header> headers = new ArrayList<Header>();
			ArrayList<ApplicationInfo> xposedModulesList = new ArrayList<ApplicationInfo>();
			List<String> activeModules = getActiveModules();

			for (PackageInfo pkg : mActivity.getPackageManager().getInstalledPackages(PackageManager.GET_META_DATA)) {
				ApplicationInfo app = pkg.applicationInfo;
				if (!app.enabled || !activeModules.contains(app.sourceDir))
					continue;

				if (app.metaData != null && app.metaData.containsKey("xposedmodule")) {
					xposedModulesList.add(app);
				}
				if (isCancelled())
					return null;
			}

			Collections.sort(xposedModulesList, new ApplicationInfo.DisplayNameComparator(pm));

			for (ApplicationInfo info : xposedModulesList) {
				Intent intent = getSettingsIntent(pm, info.packageName);
				if (intent != null) {
					Header header = XposedMod.getHeaderFromAppInfo(pm, info);
					header.intent = intent;

					headers.add(header);
				}
				if (isCancelled())
					return null;
			}
			return headers;
		}

		@Override
		protected void onPostExecute(List<Header> result) {
			mHeaders.clear();
			mHeaders.addAll(result);
			mActivity.invalidateHeaders();
			mTask = null;
		};
	}
}