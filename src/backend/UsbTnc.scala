package org.aprsdroid.app

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.util.Log
import java.io.{InputStream, OutputStream}

import net.ab0oo.aprs.parser._

import com.felhr.usbserial._

object UsbTnc {
	def deviceHandle(dev : UsbDevice) = {
		"usb_%04x_%04x_%s".format(dev.getVendorId(), dev.getProductId(), dev.getDeviceName())
	}
	def checkDeviceHandle(prefs : SharedPreferences, dev_p : android.os.Parcelable) : Boolean = {
		if (dev_p == null)
			return false
		val dev = dev_p.asInstanceOf[UsbDevice]
		val last_use = prefs.getString(deviceHandle(dev), null)
		if (last_use == null)
			return false
		prefs.edit().putString("proto", last_use)
			    .putString("link", "usb").commit()
		true
	}
}

class UsbTnc(service : AprsService, prefs : PrefsWrapper) extends AprsBackend(prefs) {
	val TAG = "APRSdroid.Usb"

	val USB_PERM_ACTION = "org.aprsdroid.app.UsbTnc.PERM"
	val ACTION_USB_ATTACHED = "android.hardware.usb.action.USB_DEVICE_ATTACHED"
	val ACTION_USB_DETACHED = "android.hardware.usb.action.USB_DEVICE_DETACHED"

	val usbManager = service.getSystemService(Context.USB_SERVICE).asInstanceOf[UsbManager];
	var thread : UsbThread = null
	var dev : UsbDevice = null
	var con : UsbDeviceConnection = null
	var ser : UsbSerialInterface = null
	var alreadyRunning = false

	val intent = new Intent(USB_PERM_ACTION)
	val pendingIntent = PendingIntent.getBroadcast(service, 0, intent, 0)

	val receiver = new BroadcastReceiver() {
		override def onReceive(ctx : Context, i : Intent) {
			Log.d(TAG, "onReceive: " + i)
			if (i.getAction() == ACTION_USB_DETACHED) {
				log("USB device detached.")
				ctx.stopService(AprsService.intent(ctx, AprsService.SERVICE))
				return
			}
			val granted = i.getExtras().getBoolean(UsbManager.EXTRA_PERMISSION_GRANTED)
			if (!granted) {
				service.postAbort(service.getString(R.string.p_serial_noperm))
				return
			}
			log("Obtained USB permissions.")
			thread = new UsbThread()
			thread.start()
		}
	}

	var proto : TncProto = null
	var sis : SerialInputStream = null

	def start() = {
		val filter = new IntentFilter(USB_PERM_ACTION)
		filter.addAction(ACTION_USB_DETACHED)
		service.registerReceiver(receiver, filter)
		alreadyRunning = true
		if (ser == null)
			requestPermissions()
		false
	}

	def log(s : String) {
		service.postAddPost(StorageDatabase.Post.TYPE_INFO, R.string.post_info, s)
	}

	def requestPermissions() {
		Log.d(TAG, "UsbTnc.requestPermissions");
		val dl = usbManager.getDeviceList();
		var requested = false
		import scala.collection.JavaConversions._
		for ((name, dev) <- dl) {
			val deviceVID = dev.getVendorId()
			val devicePID = dev.getProductId()
			if (UsbSerialDevice.isSupported(dev)) {
				// this is not a USB Hub
				log("Found USB device %04x:%04x, requesting permissions.".format(deviceVID, devicePID))
				this.dev = dev
				usbManager.requestPermission(dev, pendingIntent)
				return
			} else
				log("Unsupported USB device %04x:%04x.".format(deviceVID, devicePID))
		}
		service.postAbort(service.getString(R.string.p_serial_notfound))
	}

	def update(packet : APRSPacket) : String = {
		proto.writePacket(packet)
		"USB OK"
	}

	def stop() {
		if (alreadyRunning)
			service.unregisterReceiver(receiver)
		alreadyRunning = false
		if (ser != null)
			ser.close()
		if (sis != null)
			sis.close()
		if (con != null)
			con.close()
		if (thread == null)
			return
		thread.synchronized {
			thread.running = false
		}
		//thread.shutdown()
		thread.interrupt()
		thread.join(50)
		if (proto != null)
			proto.stop()
	}

	class UsbThread()
			extends Thread("APRSdroid USB connection") {
		val TAG = "UsbThread"
		var running = true

		def log(s : String) {
			service.postAddPost(StorageDatabase.Post.TYPE_INFO, R.string.post_info, s)
		}

		override def run() {
			val con = usbManager.openDevice(dev)
			ser = UsbSerialDevice.createUsbSerialDevice(dev, con)
			if (ser == null || !ser.syncOpen()) {
				con.close()
				service.postAbort(service.getString(R.string.p_serial_unsupported))
				return
			}
			val baudrate = prefs.getStringInt("baudrate", 115200)
			ser.setBaudRate(baudrate)
			ser.setDataBits(UsbSerialInterface.DATA_BITS_8)
			ser.setStopBits(UsbSerialInterface.STOP_BITS_1)
			ser.setParity(UsbSerialInterface.PARITY_NONE)
			ser.setFlowControl(UsbSerialInterface.FLOW_CONTROL_OFF)

			// success: remember this for usb-attach launch
			prefs.prefs.edit().putString(UsbTnc.deviceHandle(dev), prefs.getString("proto", "kiss")).commit()

			log("Opened " + ser.getClass().getSimpleName() + " at " + baudrate + "bd")
			sis = new SerialInputStream(ser)
			try {
				proto = AprsBackend.instanciateProto(service, sis, new SerialOutputStream(ser))
			} catch {
				case e : IllegalArgumentException =>
				service.postAbort(e.getMessage()); running = false
				return
			}
			service.postPosterStarted()
			while (running) {
				try {
					val line = proto.readPacket()
					Log.d(TAG, "recv: " + line)
					service.postSubmit(line)
				} catch {
				case e : Exception =>
					Log.d(TAG, "readPacket exception: " + e.toString())
					if (running) {
						service.postAbort(e.toString()); running = false
					}
				}
			}
			Log.d(TAG, "terminate()")
		}


	}

}
