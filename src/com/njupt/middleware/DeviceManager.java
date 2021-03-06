package com.njupt.middleware;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.njupt.middleware.media.Media;
import com.njupt.middleware.struct.Device;

import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

public class DeviceManager {
	private String TAG = "DeviceManager";
	private Context mContext;
	public Handler mHandler;
	private ExecutorService executor; //线程池
	private static ConcurrentHashMap<String, Device> slaveDeviceMap = new ConcurrentHashMap<String, Device>(); //存储所有连接设备
    public static ConcurrentHashMap<Media,List<Device>> currentPlaybackMap = new ConcurrentHashMap<Media, List<Device>>();
    private boolean mDeviceScanListenerFlag = false;

    //audio
    public boolean mAudioNativeLibLoadFlag = false;
    public ByteBuffer mThirdPartyBuffer; //存放从第三方播放器截取的数据
    public static int mFrameCount; //底层每次写入数据帧数，240 or 960
    public static final int DEFAULTCOUNT = 32; //默认分配缓冲区的大小，指倍数
    public static final int DEFAULTFRAME = 240;
    private AudioThirdPartyThread mAudioThirdParty; 
    public volatile boolean nativeStartPlay;

    //viedo
    public boolean mVideoNativeLibLoadFlag = false;
    public boolean mVideoNativeStartFlag = false;

    //screenrecord
    public boolean mScreenRecordNativeLibLoadFlag = false;
    public boolean mScreenRecordStartFlag = false;
	
    public DeviceManager(Context context, Handler mHandler) {
        this.mContext = context;
        this.mHandler = mHandler;
        this.executor = Executors.newCachedThreadPool();
    }

    public List<Device> getDeviceList(int type){
        List<Device> list = new ArrayList<Device>();
        if(type == Device.TYPE_AUDIO || type == Device.TYPE_AUDIO || type == Device.TYPE_PRINTER){
            for(ConcurrentMap.Entry<String,Device> e: slaveDeviceMap.entrySet() ){
//                if(e.getValue().type == type)list.add(e.getValue());
                if(deviceCheck(e.getValue().type, type))list.add(e.getValue());
            }
        }else{
            for(ConcurrentMap.Entry<String,Device> e: slaveDeviceMap.entrySet() ){
                list.add(e.getValue());
            }
        }
        return list;
    }

    public void setDeviceJobDone(String addr){
        for(HashMap.Entry<Media,List<Device>> e:currentPlaybackMap.entrySet()){
            List<Device> list = e.getValue();
            for (int i = 0; i < list.size(); i++) {
                if(list.get(i).address.getHostAddress().equals(addr)){
                    list.remove(i);
                }
            }
            if(list.size()<=0){
                Message message = new Message();
                if(e.getKey().getFileName().endsWith("pdf")){
                    message.what = 6;
                }else if(e.getKey().getFileName().endsWith("deb")){
                    message.what = 7;
                }else{
                    message.what = 8;
                }
                message.obj = e.getKey().getFileName();
                mHandler.sendMessage(message);
                currentPlaybackMap.remove(e.getKey());
            }
        }
    }
    
    public void startDeviceSacnListener(){
    	if(!mDeviceScanListenerFlag){
    		DeviceScanListener scanerlistener = new DeviceScanListener(this);
    		if(executor!=null)executor.execute(scanerlistener);
    		mDeviceScanListenerFlag = false;
    	}
    }

    boolean networkCheck(){
        String locip = BaseFunction.getLocalHostIp();
        if(locip.equals("")){
            Log.d(TAG, "vaylb->未连接至局域网络");
            Message msg = new Message();
            msg.what = 10;
            mHandler.sendMessage(msg);
            return false;
        }
        return true;
    }
    
    public void doDeviceSacn(){
        doBroadCast(UdpOrder.DEVIDE_SCANE);
    }

	public void doDevicesPrepare(String order) {
		doBroadCast(order);
	}

    public void doDevicesPrepare(String order, Map<Integer,Device> devices){
        doSingleCast(order,devices);
    }

    public void doBroadCast(String order){
        if(!networkCheck()) return;

        CommandBroadCaster castThread = new CommandBroadCaster(order);
        if (executor != null) executor.execute(castThread);
    }

    public void doRepeatBroadCast(String order,Integer repeat){
        if(!networkCheck()) return;

        CommandBroadCaster castThread = new CommandBroadCaster(order, repeat);
        if (executor != null) executor.execute(castThread);
    }

    public void doSingleCast(String order, Map<Integer,Device> devices){
        if(!networkCheck()) return;
        CommandBroadCaster castThread = new CommandBroadCaster(order,devices);
        if (executor != null) executor.execute(castThread);
    }

    public void executeRunnable(Runnable runnable){
        if (executor != null) executor.execute(runnable);
    }

    public static boolean deviceCheck(int type, int target_offset){
        if((type & (1<<target_offset)) > 0){
            return true;
        } else return false;
    }
    
    /**
     * 添加新连接的设备
     */
    public void addDevice(Device device) {
    	if(slaveDeviceMap.containsKey(device.address.getHostAddress())){
            if(slaveDeviceMap.get(device.address.getHostAddress()).type != device.type){
                slaveDeviceMap.remove(device.address.getHostAddress());
                this.slaveDeviceMap.put(device.address.getHostAddress(), device);
            }
    	}else{
            this.slaveDeviceMap.put(device.address.getHostAddress(), device);
            Log.d(TAG, "vaylb-->addDevice:"+device.address.getHostAddress()+", total:"+this.slaveDeviceMap.size());
        }
    }
    
    public int getAudioDeviceNum(){
    	int count = 0;
    	for(ConcurrentMap.Entry<String,Device> e: slaveDeviceMap.entrySet() ){
//        	if(e.getValue().type == Device.TYPE_AUDIO)count++;
            if(deviceCheck(e.getValue().type, Device.TYPE_AUDIO))count++;
		}
    	return count;
    }
    
    public void startAudioPlayBack(File file,int num){
    	AudioTransferThread audiotransfer = new AudioTransferThread(this,file,num);
		if(executor!=null)executor.execute(audiotransfer);
    }
    
    public void startAudioThirdParty(){
    	mAudioThirdParty = new AudioThirdPartyThread(this);
		if(executor!=null)executor.execute(mAudioThirdParty);
    }
    
    public void loadAudioNativeLib(){
    	if(!mAudioNativeLibLoadFlag){
    		System.loadLibrary("audio_host_middleware");
    		mAudioNativeLibLoadFlag = true;
    	}
    }
    
    // 设置Buffer
    public void setThirdPartyBuffer() {
        mFrameCount = native_setup(0, 0); // 240 or 960
        if (mFrameCount <= 0)
            mFrameCount = DEFAULTFRAME;
        mThirdPartyBuffer = ByteBuffer.allocateDirect(mFrameCount * DEFAULTCOUNT);
        native_setbuffertemp(mThirdPartyBuffer);
    }
    
    
    public int audioThirdPartyStart() {
		if (mAudioThirdParty != null) {
			Log.d(TAG, "vaylb->native start");
			//commandCast(UdpOrder.STANDBY_FALSE);
			// make sure fromJni can call by native
			fromJni(7);
			native_setstartflag(1);
		}
        return 0;
    }

    public int audioThirdPartyStop() {
        if(mAudioNativeLibLoadFlag)native_setstartflag(0);
        return 0;
    }
    
    /*
     * called by native HostProcessThread
     */
    private void fromJni(int i) {
        switch (i) {
            case 1:
            	//commandCast(UdpOrder.STANDBY_FALSE);
            	mAudioThirdParty.start();
                break;
            case 2:
                nativeStartPlay = false;
                mAudioThirdParty.stop();
                //commandCast(UdpOrder.STANDBY_TRUE);
                break;
            case 3:
            	mAudioThirdParty.signalToRead();
                break;
            case 4:
                Message msg = new Message();
                msg.what = 3;
                mHandler.sendMessage(msg);
                break;
            default:
                break;
        }

    }

    //audio online
    public void startAudioOnlinePlayBack(String songName,int mediasize,int num){
        OnlineThread audioOnline = new OnlineThread(this,"http://182.254.211.166:8080/"+songName, OnlineThread.DEFAULT_AUDIO_PORT,mediasize,num);
        if(executor!=null)executor.execute(audioOnline);
    }


    //video part

    public void startVideoPlayBack(File file,int num){
        VideoTransferThread videotransfer = new VideoTransferThread(this,file,num);
        if(executor!=null)executor.execute(videotransfer);
    }

    public void startVideoOnlinePlayBack(String movieName,int mediasize,int num){
        OnlineThread videoOnline = new OnlineThread(this,"http://182.254.211.166:8080/"+movieName, OnlineThread.DEFAULT_VIDEO_PORT,mediasize,num);
        if(executor!=null)executor.execute(videoOnline);
    }

    public void loadVideoNativeLib(){
        if(!mVideoNativeLibLoadFlag){
            System.loadLibrary("video_host_middleware");
            mVideoNativeLibLoadFlag = true;
        }
    }

    public int getVideoDeviceNum(){
        int count = 0;
        for(ConcurrentMap.Entry<String,Device> e: slaveDeviceMap.entrySet() ){
//            if(e.getValue().type == Device.TYPE_VIDEO)count++;
            if(deviceCheck(e.getValue().type, Device.TYPE_VIDEO))count++;
        }
        return count;
    }

    public void setVideoNativeSlaveNum(){
        native_setslavenum(getVideoDeviceNum());
    }

    public void videoNativeStart(){
        mVideoNativeStartFlag = true;
        native_setvideohook(1);
    }

    //screenrecord

    public void loadScreenRecordNativeLib(){
        if(!mScreenRecordNativeLibLoadFlag){
            System.loadLibrary("screenrecord_middleware");
            mScreenRecordNativeLibLoadFlag = true;
        }
    }


    //parms
    String screenSize = "1080x1920";//"720x1080"
    String screenBitRate = "200M";
    String screenFormat = "frames"; //mp4,h264,frames
    String screenFilepath = "/sdcard/screenrecord.264";
    int screenRotate = 0;

    public void setupScreenRecord() {
        loadScreenRecordNativeLib();
        native_screenrecord_setup(screenSize, screenBitRate, screenRotate, screenFormat, screenFilepath);
    }

    public void startScreenRecord(){
        native_screenrecord_start(screenFilepath);
    }

    public void setScreenRecordSlaveNum(){
        Log.e(TAG, "vaylb->setScreenRecordSlaveNum");
        mScreenRecordStartFlag = true;
        native_screenrecord_setslavenum(getVideoDeviceNum());
    }

    public void stopScreenRecord(){
        mScreenRecordStartFlag = false;
        mScreenRecordNativeLibLoadFlag = false;
        native_screenrecord_stop();
    }

    //file print
    public void startPrintFile(File file,int num){
        FileTransferThread filetransfer = new FileTransferThread(this,file,num);
        if(executor!=null)executor.execute(filetransfer);
    }

    // native函数
    //audio
    public native int native_setup(int receivebuffer, int sendbuffer);
    public native void native_setstartflag(int flag);
    public native boolean native_checkstandbyflag();
    public static native boolean native_checkreadpos();
    public static native boolean native_checkexitflagI();
    public static native boolean native_checkexitflagII();
    public static native void native_setreadpos(int pos);
    public static native void native_setplayflag(long time_java);
    public native void native_setbuffertemp(ByteBuffer buffer);
    public native void native_exit();
    public native int native_haswrite();
    public native void native_read_ahead(int readahead);
    public native boolean native_needcheckwrited();
    public native void native_signaleToWrite();

    //video
    public native void native_setvideohook(int flag);
    public native void native_setslavenum(int num);
    public native void native_setscreensplit(int flag);

    //screenrecord
    public native void native_screenrecord_setup(String size, String bitrate, int rotate, String format, String filepath);
    public native void native_screenrecord_start(String filepath);
    public native void native_screenrecord_setslavenum(int num);
    public native void native_screenrecord_stop();
}
