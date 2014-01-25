package de.janbo.agendawatchface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;

/**
 * Handles installation of the watchface on the Pebble
 * @author Jan, thanks to matejdro
 *
 */
public class WatchappHandler {
	// Thanks to matejdro for the code
	public static void install(Context context) {
		File publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);

		File watchappFile = new File(publicDir, "agenda_watchface.pbw");

		// Copy file from assets
		try {
			publicDir.mkdirs();
			InputStream myInput = context.getAssets().open("watchface.pbw");
			OutputStream myOutput = new FileOutputStream(watchappFile);

			byte[] buffer = new byte[1024];
			int length;
			while ((length = myInput.read(buffer)) > 0) {
				myOutput.write(buffer, 0, length);
			}

			myOutput.close();
			myInput.close();

		} catch (IOException e) {
			e.printStackTrace();
			return;
		}

		Intent intent = new Intent(Intent.ACTION_VIEW);
		intent.setDataAndType(Uri.fromFile(watchappFile), "application/octet-stream");
		try {
			context.startActivity(intent);

		} catch (ActivityNotFoundException e) {
			AlertDialog.Builder builder = new AlertDialog.Builder(context);
			builder.setMessage("Watchapp installation has failed. Do you have Pebble app installed?").setNegativeButton("OK", null).show();
		}
	}
}
