package com.xiami;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

import org.apache.commons.io.IOUtils;
import org.jsoup.Jsoup;
import org.jsoup.helper.StringUtil;
import org.jsoup.nodes.Document;
import org.json.*;


public class XiamiDownloadAlbum {
	private ArrayList<MyProxy> myproxyList = null;
	
	class MyProxy{
		public String host, username, password, line;
		int port;
		public MyProxy(String line) {
			this.line = line;
			String[] tmps = line.split(",");
			host = tmps[0];
			port = Integer.parseInt(tmps[1]);
			username = tmps[2];
			password = tmps[3];
		}
		
		public String toString() {
			return line;
		}
	}
	
	public void setProvider(String filepath)
	{
		myproxyList = new ArrayList<MyProxy>();
		BufferedReader bufr;
		try {
			bufr = new BufferedReader(new InputStreamReader(
					new FileInputStream(filepath), "UTF-8"));
			String line = null;
			while( (line = bufr.readLine())!=null ) {
				//61.160.221.41 888 tyt0308 tyt0308
				myproxyList.add(new MyProxy(line));
			}
			bufr.close();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	public InputStream proxyDownload(String urlStr, int delay) throws Exception {
		while(true) {
			try{
				if(myproxyList!=null && myproxyList.size()>0) {
					URL url = new URL(urlStr);
					URLConnection conn;
					int idx = Math.abs((urlStr+System.currentTimeMillis()).hashCode()) % myproxyList.size();
					InetSocketAddress addr = new InetSocketAddress(myproxyList.get(idx).host, myproxyList.get(idx).port);  
			        Proxy proxy = new Proxy(Proxy.Type.HTTP, addr);
			        String headerkey = "Proxy-Authorization";
			        String headStr = myproxyList.get(idx).username+":"+myproxyList.get(idx).password;
		            String headerValue = "Basic "+ Base64.getEncoder().encodeToString(headStr.getBytes());
		            conn = url.openConnection(proxy);
		            conn.setConnectTimeout(delay);
		            conn.setRequestProperty(headerkey, headerValue);
		            //System.out.println(myproxyList.get(idx));
		            InputStream inPage = conn.getInputStream();
		            return inPage;
				} else{
					URL url = new URL(urlStr);
					URLConnection conn = url.openConnection();
			        conn.setConnectTimeout(delay);
			        InputStream inPage = conn.getInputStream();
			        return inPage;
				}
			} catch (Exception e) {
				delay += 1000;
				if(delay>10000)
					throw e;
			    try {
			    	Thread.sleep(500);
			    } catch (InterruptedException ei) {
			    	ei.printStackTrace();
			    }
			}
		}
	}
	
	/*
	 *	5 rows, first construct col by col
	 *	fill in data row by row
	 *	read data col by col
		********
		********
		********
		*******
		*******
	*/
	public String decodeLocation(String location) throws Exception {
		String url = location.substring(1);
		int urlLen = url.length();
		int rows = location.charAt(0) - '0';
		int cols_base = urlLen / rows;
		int rows_ex = urlLen % rows;
		char [][]matrix = new char[rows][cols_base+1];
		int cnt = 0;
		for (int i = 0; i < rows; ++i) {
			int cols = cols_base + (i<rows_ex?1:0);
			for (int j = 0; j < cols; ++j)
				matrix[i][j] = url.charAt(cnt++);
		}
		char []ret = new char[urlLen];
		for (int i = 0; i < urlLen; ++i)
			ret[i] = matrix[i%rows][i/rows];
		return URLDecoder.decode(new String(ret), "utf-8").replace('^', '0');
	}
	
	public String mkdirLevel(String name){
		ArrayList<String> dirs = new ArrayList<String>();
		int interval = 2, max_interval = 2;
		for(int i = 0; i < max_interval; ++i) {
			String subDir = name.substring(interval*i, Math.min(interval*(i+1), name.length()));
			dirs.add(subDir);
		}
		return StringUtil.join(dirs, "/");
	}
	
	class JsonDownloader implements Runnable {
		final private String JsonStr= "http://www.xiami.com/song/playlist/id/%d/type/1/cat/json";
		private LinkedHashMap<Long, Integer> albumsList = new LinkedHashMap<Long, Integer>();
		private LinkedHashMap<Long,String> songsList = new LinkedHashMap<Long,String>();
		
		public JsonDownloader(String alumsFile) {
			try (BufferedReader bufr = new BufferedReader(new InputStreamReader(
						new FileInputStream(alumsFile), "UTF-8"))) {
				String line = null;
				while( (line = bufr.readLine())!=null ) 
					albumsList.put(Long.parseLong(line), 0);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		
		@Override
		public void run() {
			while(true) {
				long albumID;
				int delay;
				synchronized (this.albumsList) {
					if(albumsList.isEmpty())
						break;
					Map.Entry<Long, Integer> idEntry = albumsList.entrySet().iterator().next();
					albumID = idEntry.getKey();
					delay = idEntry.getValue();
					albumsList.remove(albumID);
			        if(albumsList.size()%100==0)
						System.out.println("toCrawlAlums : " + albumsList.size());
				}
				if(delay>10000)
					continue;
				String jsonUrl = String.format(JsonStr,albumID);
				try {
					String jsonContent = IOUtils.toString(proxyDownload(jsonUrl, delay), "utf-8");
					JSONObject jsonObj= new JSONObject(jsonContent);
					JSONArray songsObj = null;
					try {
						songsObj = jsonObj.getJSONObject("data").getJSONArray("trackList");
					} catch (JSONException e) {
						System.err.println("Payed album error : " + albumID + ", " + jsonUrl);
						continue;
					}
					ArrayList<String> songsUrl = new ArrayList<String>();
					ArrayList<Long> songsID = new ArrayList<Long>();
					for (int i = 0; i < songsObj.length(); ++i) {
						songsUrl.add(decodeLocation(songsObj.getJSONObject(i).getString("location")));
						songsID.add(songsObj.getJSONObject(i).getLong("songId"));
					}
					synchronized (this.songsList) {
						for (int i = 0; i < songsUrl.size(); ++i)
							songsList.put(songsID.get(i), songsUrl.get(i));
					}
					System.out.println(Thread.currentThread().getName() + " saved album : " + albumID);
				} catch (Exception e) {
					System.err.println("Download json error : " + albumID + ", " + jsonUrl);
					synchronized (this.albumsList) {
						albumsList.put(albumID, delay + 1000);
					}
					e.printStackTrace();
				    try {
				    	Thread.sleep(2000);
				    } catch (InterruptedException ei) {
				    	ei.printStackTrace();
				    }
				}
			}
			System.out.println(Thread.currentThread().getName() + " ends successfully!");
		}
		
	}
	
	class SongDownloader implements Runnable {
		private LinkedHashMap<Long, Integer> songsList = new LinkedHashMap<Long, Integer>();
		private HashMap<Long, String> songsUrlMap = new HashMap<Long, String>();
		public SongDownloader(LinkedHashMap<Long,String> refList) {
			songsUrlMap = refList;
			for (Map.Entry<Long, String> entry : refList.entrySet()) {
				songsList.put(entry.getKey(), 0);
			}
		}
		@Override
		public void run() {
			while(true) {
				long songID;
				int delay;
				synchronized (this.songsList) {
					if(songsList.isEmpty())
						break;
					Map.Entry<Long, Integer> idEntry = songsList.entrySet().iterator().next();
					songID = idEntry.getKey();
					delay = idEntry.getValue();
					songsList.remove(songID);
			        if(songsList.size()%100==0)
						System.out.println("toCrawlSongs : " + songsList.size());
				}
				if(delay>10000)
					continue;
				String songUrl = songsUrlMap.get(songID);
				try {
					String songFolder = "xiami_mp3/" + mkdirLevel(""+songID);
					new File(songFolder).mkdirs();
					InputStream inPage = proxyDownload(songUrl, delay);
			        try (FileOutputStream out = new FileOutputStream(songFolder + "/" + songID + ".mp3")) {
			            IOUtils.copy(inPage, out);
			        }
					System.out.println(Thread.currentThread().getName() + " downloaded song : " + songID);
				} catch (Exception e) {
					System.err.println("Download song error : " + songID + ", " + songUrl);
					synchronized (this.songsList) {
						songsList.put(songID, delay + 1000);
					}
					e.printStackTrace();
				    try {
				    	Thread.sleep(2000);
				    } catch (InterruptedException ei) {
				    	ei.printStackTrace();
				    }
				}
			}
			System.out.println(Thread.currentThread().getName() + " ends successfully!");
		}
		
	}
	
	public static void main(String[] args) throws Exception {
		int maxThreads = 10;
		
		XiamiDownloadAlbum downloader = new XiamiDownloadAlbum();
		downloader.setProvider("config/provider.txt");
		
		
		
		ArrayList<Thread> threads;
		try {
			JsonDownloader jsonThread = downloader.new JsonDownloader("config/albums.txt");
			threads = new ArrayList<Thread>();
			for (int i = 0; i < maxThreads; i++) threads.add(new Thread(jsonThread,"JsonThread "+i));
			for (int i = 0; i < maxThreads; i++) threads.get(i).start();
			for (int i = 0; i < maxThreads; i++) threads.get(i).join();
			
			SongDownloader songThread = downloader.new SongDownloader(jsonThread.songsList);
			threads = new ArrayList<Thread>();
			for (int i = 0; i < maxThreads; i++) threads.add(new Thread(songThread,"SongThread "+i));
			for (int i = 0; i < maxThreads; i++) threads.get(i).start();
			for (int i = 0; i < maxThreads; i++) threads.get(i).join();
		} catch ( Exception e) {
			e.printStackTrace();
		}
	}
}
