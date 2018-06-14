/*
 * Copyright 2015-2016 Nickolay Savchenko
 * Copyright 2017-2018 Nikita Shakarun
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.playsoftware.j2meloader;

import android.Manifest;
import android.arch.persistence.room.Room;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.FragmentManager;
import android.support.v4.content.ContextCompat;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.ViewConfiguration;
import android.widget.Toast;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import javax.microedition.shell.ConfigActivity;

import ru.playsoftware.j2meloader.applist.AppItem;
import ru.playsoftware.j2meloader.applist.AppsListAdapter;
import ru.playsoftware.j2meloader.applist.AppsListFragment;
import ru.playsoftware.j2meloader.appsdb.AppDatabase;
import ru.playsoftware.j2meloader.appsdb.AppItemDao;
import ru.playsoftware.j2meloader.base.BaseActivity;
import ru.playsoftware.j2meloader.dialogs.AboutDialogFragment;
import ru.playsoftware.j2meloader.dialogs.HelpDialogFragment;
import ru.playsoftware.j2meloader.settings.SettingsActivity;
import ru.playsoftware.j2meloader.util.FileUtils;
import ru.playsoftware.j2meloader.util.JarConverter;

public class MainActivity extends BaseActivity {

	private AppItemDao appItemDao;
	private AppsListFragment appsListFragment;
	private SharedPreferences sp;
	private static final int MY_PERMISSIONS_REQUEST_WRITE_STORAGE = 0;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		Uri uri = getIntent().getData();
		if (!isTaskRoot() && uri == null) {
			finish();
			return;
		}
		if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
			Toast.makeText(this, R.string.external_storage_not_mounted, Toast.LENGTH_SHORT).show();
			finish();
		}
		sp = PreferenceManager.getDefaultSharedPreferences(this);
		if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
				!= PackageManager.PERMISSION_GRANTED) {
			ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
					MY_PERMISSIONS_REQUEST_WRITE_STORAGE);
		} else {
			setupActivity();
			if (savedInstanceState == null && uri != null) {
				JarConverter converter = new JarConverter(this);
				try {
					converter.execute(FileUtils.getJarPath(this, uri));
				} catch (FileNotFoundException e) {
					e.printStackTrace();
				}
			} else {

				//Uri.parse("file:///android_asset/app.jad");

				try {
					String jadFilePath = copyFromAsset("app.jad");
					copyFromAsset("app.jar");

					JarConverter converter = new JarConverter(this);
					converter.execute(jadFilePath).get();


					AppItem item = (AppItem) appsListFragment.getListAdapter().getItem(0);
					Intent i = new Intent(Intent.ACTION_DEFAULT, Uri.parse(item.getPathExt()), this, ConfigActivity.class);
					startActivity(i);


				} catch (IOException e) {
					e.printStackTrace();
				} catch (InterruptedException e) {
					e.printStackTrace();
				} catch (ExecutionException e) {
					e.printStackTrace();
				}

			}


		}
	}

	private String copyFromAsset(String assetPath) throws IOException {

		File dirTmp = new File(getApplicationInfo().dataDir, "tmp");
		dirTmp.mkdir();

		File dest = new File(dirTmp, new File(assetPath).getName());

		try (InputStream is = getAssets().open(assetPath); OutputStream os = new FileOutputStream(dest)) {

			byte[] buffer = new byte[1024];
			int read;
			while((read = is.read(buffer)) != -1){
				os.write(buffer, 0, read);
			}
		}

		return dest.getAbsolutePath();

	}
	private void setupActivity() {
		initFolders();
		checkActionBar();
		appsListFragment = new AppsListFragment();
		ArrayList<AppItem> apps = new ArrayList<>();
		AppsListAdapter adapter = new AppsListAdapter(this, apps);
		appsListFragment.setListAdapter(adapter);
		FragmentManager fragmentManager = getSupportFragmentManager();
		fragmentManager.beginTransaction()
				.replace(R.id.container, appsListFragment).commitAllowingStateLoss();
		initDb();
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[],
										   @NonNull int[] grantResults) {
		switch (requestCode) {
			case MY_PERMISSIONS_REQUEST_WRITE_STORAGE:
				if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
					setupActivity();
				} else {
					Toast.makeText(this, R.string.permission_request_failed, Toast.LENGTH_SHORT).show();
					finish();
				}
				break;
		}
	}

	private void initDb() {
		AppDatabase db = Room.databaseBuilder(this,
				AppDatabase.class, "apps-database.db").allowMainThreadQueries().build();
		appItemDao = db.appItemDao();
		if (!FileUtils.checkDb(this, appItemDao.getAllByName())) {
			appItemDao.insertAll(FileUtils.getAppsList(this));
		}
		updateAppsList();
	}

	private void initFolders() {
		File nomedia = new File(ConfigActivity.EMULATOR_DIR, ".nomedia");
		if (!nomedia.exists()) {
			try {
				nomedia.getParentFile().mkdirs();
				nomedia.createNewFile();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	private void checkActionBar() {
		boolean firstStart = sp.getBoolean("pref_first_start", true);
		if (firstStart) {
			if (!ViewConfiguration.get(this).hasPermanentMenuKey()) {
				sp.edit().putBoolean("pref_actionbar_switch", true).apply();
			}
			sp.edit().putBoolean("pref_first_start", false).apply();
		}
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.main, menu);
		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
			case R.id.action_about:
				AboutDialogFragment aboutDialogFragment = new AboutDialogFragment();
				aboutDialogFragment.show(getSupportFragmentManager(), "about");
				break;
			case R.id.action_settings:
				Intent settingsIntent = new Intent(this, SettingsActivity.class);
				startActivity(settingsIntent);
				break;
			case R.id.action_help:
				HelpDialogFragment helpDialogFragment = new HelpDialogFragment();
				helpDialogFragment.show(getSupportFragmentManager(), "help");
				break;
			case R.id.action_exit_app:
				finish();
				break;
		}
		return super.onOptionsItemSelected(item);
	}

	public void addApp(AppItem item) {
		appItemDao.insert(item);
		updateAppsList();
	}

	public void deleteApp(AppItem item) {
		appItemDao.delete(item);
		updateAppsList();
	}

	public void deleteAllApps() {
		appItemDao.deleteAll();
	}

	private void updateAppsList() {
		String appSort = sp.getString("pref_app_sort", "name");
		List<AppItem> apps;
		if (appSort.equals("name")) {
			apps = appItemDao.getAllByName();
		} else {
			apps = appItemDao.getAllByDate();
		}
		AppsListAdapter adapter = (AppsListAdapter) appsListFragment.getListAdapter();
		adapter.setItems(apps);
		adapter.notifyDataSetChanged();
	}
}
