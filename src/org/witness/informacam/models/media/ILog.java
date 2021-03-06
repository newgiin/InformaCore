package org.witness.informacam.models.media;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONException;
import org.json.JSONObject;
import org.witness.informacam.InformaCam;
import org.witness.informacam.R;
import org.witness.informacam.crypto.EncryptionUtility;
import org.witness.informacam.models.j3m.IDCIMEntry;
import org.witness.informacam.models.notifications.INotification;
import org.witness.informacam.models.organizations.IOrganization;
import org.witness.informacam.models.transport.ITransportStub;
import org.witness.informacam.storage.IOUtility;
import org.witness.informacam.transport.TransportUtility;
import org.witness.informacam.utils.Constants.App.Storage;
import org.witness.informacam.utils.Constants.App.Storage.Type;
import org.witness.informacam.utils.Constants.Codes;
import org.witness.informacam.utils.Constants.Models;
import org.witness.informacam.utils.Constants.Models.IMedia.MimeType;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Base64;
import android.util.Log;

public class ILog extends IMedia {
	public long autoLogInterval = 10 * (60 * 1000);	// 10 minutes?
	public boolean shouldAutoLog = false;

	public long startTime = 0L;
	public long endTime = 0L;

	public List<String> attachedMedia = new ArrayList<String>();
	
	private Handler proxyHandler;
	private Map<String, InputStream> j3mZip;
	
	public ILog() {
		super();
		
		_id = generateId("log_" + System.currentTimeMillis());		
		
		dcimEntry = new IDCIMEntry();
		dcimEntry.mediaType = MimeType.LOG;
		
		info.guardianproject.iocipher.File rootFolder = new info.guardianproject.iocipher.File(_id);
		if(!rootFolder.exists()) {
			rootFolder.mkdir();
		}

		this.rootFolder = rootFolder.getAbsolutePath();
	}

	public ILog(IMedia media) {
		super();
		inflate(media.asJson());
	}
	
	public void sealLog(boolean share, IOrganization organization, INotification notification) throws IOException {
		InformaCam informaCam = InformaCam.getInstance();
		
		
		// zip up everything, encrypt if required
		String logName = ("log_" + System.currentTimeMillis() + ".zip");
		
		if(share) {
			java.io.File log = new java.io.File(Storage.EXTERNAL_DIR, logName);
			IOUtility.zipFiles(j3mZip, log.getAbsolutePath(), Type.FILE_SYSTEM);

			if(organization != null) {
				byte[] j3mBytes = informaCam.ioService.getBytes(log.getAbsolutePath(), Type.FILE_SYSTEM);
				j3mBytes = EncryptionUtility.encrypt(j3mBytes, Base64.encode(informaCam.ioService.getBytes(organization.publicKey, Type.IOCIPHER), Base64.DEFAULT));
				informaCam.ioService.saveBlob(j3mBytes, log, true);
			}

		} else {
			info.guardianproject.iocipher.File log = new info.guardianproject.iocipher.File(rootFolder, logName);
			IOUtility.zipFiles(j3mZip, log.getAbsolutePath(), Type.IOCIPHER);

			if(organization != null) {
				byte[] j3mBytes = informaCam.ioService.getBytes(log.getAbsolutePath(), Type.IOCIPHER);
				if (!debugMode)
				{
					j3mBytes = EncryptionUtility.encrypt(j3mBytes, Base64.encode(informaCam.ioService.getBytes(organization.publicKey, Type.IOCIPHER), Base64.DEFAULT));
					j3mBytes = Base64.encode(j3mBytes, Base64.DEFAULT);
				}
				informaCam.ioService.saveBlob(j3mBytes, log);
				
				ITransportStub submission = new ITransportStub(organization, notification);
				submission.setAsset(log.getName(), log.getAbsolutePath(), MimeType.LOG);
				
				
				TransportUtility.initTransport(submission);
			}
		}
		
		reset();
	}
	
	@SuppressLint("HandlerLeak")
	@Override
	public boolean export(final Context context, Handler h, final IOrganization organization, final boolean share) throws FileNotFoundException {
		InformaCam informaCam = InformaCam.getInstance();
		
		Log.d(LOG, "exporting a log!");
		proxyHandler = h;
		j3mZip = new HashMap<String, InputStream>();

		final INotification notification = new INotification();

		responseHandler = new Handler() {
			public int mediaHandled = 0;
			
			@Override
			public void handleMessage(Message msg) {
				Bundle b = msg.getData();
				
				if(b.containsKey(Models.IMedia.VERSION)) {
					InformaCam informaCam = InformaCam.getInstance();
					String version = b.getString(Models.IMedia.VERSION);
										
					InputStream versionBytes = informaCam.ioService.getStream(version, Type.IOCIPHER);
					j3mZip.put(version.substring(version.lastIndexOf("/") + 1), versionBytes);
					
					mediaHandled++;
					
					if(mediaHandled == ILog.this.attachedMedia.size()) {
						
						try
						{
							Log.d(LOG, "Handled all the media!");
							sealLog(share, organization, notification);
						}
						catch (IOException ioe)
						{
							Log.e(LOG, "unable to sealLog()",ioe);
						}
					}
				}
			}
		};

		int progress = 0;

		// its icon will probably be some sort of stock thing
		
		// append its data sensory data, form data, etc.
		mungeData();
		
		mungeSensorLogs(proxyHandler);
		progress += 5;
		sendMessage(Codes.Keys.UI.PROGRESS, progress);
		
		mungeGenealogyAndIntent();
		genealogy.dateCreated = this.startTime;
		progress += 5;
		sendMessage(Codes.Keys.UI.PROGRESS, progress);

		notification.label = context.getString(R.string.export);
		notification.mediaId = this._id;
		notification.content = context.getString(R.string.you_exported_this_x, "log");
		if(organization != null) {
			intent.intendedDestination = organization.organizationName;
			notification.content = context.getString(R.string.you_exported_this_x_to_x, "log", organization.organizationName);
		}
		progress += 5;
		sendMessage(Codes.Keys.UI.PROGRESS, progress);

		JSONObject j3mObject = null;
		try {
			j3mObject = new JSONObject();
			JSONObject j3m = new JSONObject();
			
			j3m.put(Models.IMedia.j3m.DATA, data.asJson());
			j3m.put(Models.IMedia.j3m.GENEALOGY, genealogy.asJson());
			j3m.put(Models.IMedia.j3m.INTENT, intent.asJson());
			j3mObject.put(Models.IMedia.j3m.SIGNATURE, new String(informaCam.signatureService.signData(j3m.toString().getBytes())));
			j3mObject.put(Models.IMedia.j3m.J3M, j3m);
			Log.d(LOG, "here we have a start at j3m:\n" + j3mObject.toString());

			j3mZip.put("log.j3m", new ByteArrayInputStream(j3mObject.toString().getBytes()));

			progress += 5;
			sendMessage(Codes.Keys.UI.PROGRESS, progress);

			notification.generateId();
			notification.taskComplete = false;
			// XXX: maybe proxyHandler?
			informaCam.addNotification(notification, responseHandler);

		} catch(JSONException e) {
			Log.e(LOG, e.toString(),e);
		}
		
		if(attachedMedia != null && attachedMedia.size() > 0) {
			data.attachments = getAttachedMediaIds();
			
			int progressIncrement = (int) (50/(attachedMedia.size() * 2));

			for(final String s : attachedMedia) {
				// exported only to iocipher! not a share!
				new Thread(new Runnable() {
					@Override
					public void run() {
						IMedia m = InformaCam.getInstance().mediaManifest.getById(s);
						
						if(m.associatedCaches == null) {
							m.associatedCaches = new ArrayList<String>();
						}
						
						if (associatedCaches != null)
							m.associatedCaches.addAll(associatedCaches);
						
						try {
							m.export(context, responseHandler, null, false);
						} catch (FileNotFoundException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
					}
				}).start();
				
				progress += progressIncrement;
				sendMessage(Codes.Keys.UI.PROGRESS, progress);
				
			}
		} else {
			
			try
			{
				sealLog(share, organization, notification);
			}
			catch (IOException ioe)
			{
				Log.e(LOG,"error sealLeg() on export",ioe);
				return false;
			}
		}

		return true;
	}
	
	private List<String> getAttachedMediaIds() {
		return attachedMedia;
	}

	@Override
	protected void sendMessage(String key, int what) {
		Bundle b = new Bundle();
		b.putInt(key, what);
		Message msg = new Message();
		msg.setData(b);

		proxyHandler.sendMessage(msg);
	}

	@Override
	protected void sendMessage(String key, String what) {
		Bundle b = new Bundle();
		b.putString(key, what);
		Message msg = new Message();
		msg.setData(b);

		proxyHandler.sendMessage(msg);
	}
}
