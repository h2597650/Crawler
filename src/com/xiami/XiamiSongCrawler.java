package com.xiami;

import java.util.*;
import java.util.Map.Entry;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.regex.*;
import java.net.Proxy;
import java.io.File;

import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.LogFactory;
import org.jsoup.Connection;
import org.jsoup.Connection.Method;
import org.jsoup.Connection.Response;
import org.jsoup.Jsoup;
import org.jsoup.helper.HttpConnection;
import org.jsoup.helper.StringUtil;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.json.JSONObject;
import org.json.JSONArray;

import com.gargoylesoftware.htmlunit.BrowserVersion;
import com.gargoylesoftware.htmlunit.CookieManager;
import com.gargoylesoftware.htmlunit.DefaultCredentialsProvider;
import com.gargoylesoftware.htmlunit.FailingHttpStatusCodeException;
import com.gargoylesoftware.htmlunit.IncorrectnessListener;
import com.gargoylesoftware.htmlunit.NicelyResynchronizingAjaxController;
import com.gargoylesoftware.htmlunit.ProxyConfig;
import com.gargoylesoftware.htmlunit.ScriptException;
import com.gargoylesoftware.htmlunit.SilentCssErrorHandler;
import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.html.*;
import com.gargoylesoftware.htmlunit.javascript.JavaScriptErrorListener;
import com.gargoylesoftware.htmlunit.util.Cookie;



public class XiamiSongCrawler implements Runnable {

	
	private LinkedHashMap<String, Integer> toCrawlList, artistList;
	private HashSet<String> crawledList; 
	
	private String baseUrl, startID;
	private long maxCnt, cnt;
	private String basePath;
	private Object writeLock, fetchLock;
	private ArrayList<Map.Entry<String, Integer>> proxyList = null;
	private ArrayList<DefaultCredentialsProvider> providerList = null;
	private ArrayList<MyProxy> myproxyList = null;
	
	private Map<String, String> userCookies;
	private CookieManager cookieManager;
	
	
	public XiamiSongCrawler(String baseUrl, String artistFile, long maxCnt, String xmlFile)
	{
		this.baseUrl = baseUrl;
		this.maxCnt = maxCnt;
		this.basePath = xmlFile;
		this.cnt = 0L;
		this.writeLock = new Object();
		this.fetchLock = new Object();
		crawledList = new HashSet<String>();
		toCrawlList = new LinkedHashMap<String,Integer>();
		artistList = new LinkedHashMap<String,Integer>();
		
		BufferedReader bufr;
		try {
			bufr = new BufferedReader(new InputStreamReader(
					new FileInputStream(artistFile), "UTF-8"));
			String line = null;
			while( (line = bufr.readLine())!=null ) {
				artistList.put(line, 0);
			}
			bufr.close();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	public void run(){
		crawl(baseUrl, basePath);
	}
	
	public void crawl(String baseUrl, String filePath) 
	{
		
		while(true) {
			String artistID = null;
			int delay;
			synchronized (this.artistList) {
				if(artistList.isEmpty())
					break;
				Map.Entry<String, Integer> idEntry = artistList.entrySet().iterator().next();
				artistID = idEntry.getKey();
				delay = idEntry.getValue();
				artistList.remove(artistID);
		        if(artistList.size()%100==0)
					System.out.println("toCrawlList : " + artistList.size());
			}
			if(delay>10000)
				continue;
			String pageurl = baseUrl + artistID;
			try {
				// extract album list
				String alubmMainUrl = baseUrl + "album-" + artistID;
				String albumMainPage = proxyConnect(alubmMainUrl, 5000+delay);
				Document albumMainDoc = Jsoup.parse(albumMainPage);
				Element albumCntEle = albumMainDoc.select("div.cate_viewmode").select(".clearfix >p.counts").first();
				int numsFirst = 0, numsLast = 0;
				for (int i = 0; i < albumCntEle.text().length(); ++i)
					if (Character.isDigit(albumCntEle.text().charAt(i))) {
						numsFirst = i;
						break;
					}
				for (int i = albumCntEle.text().length() - 1; i >= 0; --i)
					if (Character.isDigit(albumCntEle.text().charAt(i))) {
						numsLast = i;
						break;
					}
				int albumCnt = Integer.parseInt(albumCntEle.text().substring(numsFirst, numsLast + 1));
				int albumPageCnt = (albumCnt - 1) / 9 + 1;
				ArrayList<String> albumList = new ArrayList<String>();
				for (int idx = 1; idx <= albumPageCnt; ++idx) {
					String albumsPage = proxyConnect(alubmMainUrl + "?page=" + idx, 5000+delay);
					Document albumsDoc = Jsoup.parse(albumsPage);
					Elements albumEles = albumsDoc.select("div.album_item100_thread > div.info a.CDcover100");

					for (Element albumEle : albumEles) {
						String albumHref = albumEle.attr("href");
						albumHref = extractID(albumHref);
						albumList.add(albumHref);
					}
				}
				// System.out.println(albumList.size());
				// save album pages
				for (String albumID : albumList) {
					String albumURL = "http://www.xiami.com/album/" + albumID;
					String albumPage = proxyConnect(albumURL, 5000+delay);
					Document albumDoc = Jsoup.parse(albumPage);
					String folder = basePath + mkdirLevel(artistID) + "/" + artistID;
					String albumFolder = folder + "/" +albumID;
					mkdirs(albumFolder);
					try ( BufferedWriter bufw = new BufferedWriter(new OutputStreamWriter(
							new FileOutputStream(albumFolder + "/" + albumID + ".album"), "UTF-8"))) {
						bufw.write(annotateURL(albumURL) + albumDoc.html());
						bufw.close();
					}
					System.out.println(Thread.currentThread().getName() + " saved album : " + albumID);

					Elements songEles = albumDoc.select("td.song_name");
					for (Element songEle : songEles) {
						String songHref = songEle.select("a").first().attr("href");
						songHref = extractID(songHref);
						String songURL = "http://www.xiami.com/song/" + songHref;
						String songPage = proxyConnect(songURL, 5000+delay);
						Document songDoc = Jsoup.parse(songPage);
						try (BufferedWriter bufw = new BufferedWriter(new OutputStreamWriter(
								new FileOutputStream(albumFolder + "/" + songHref + ".song"), "UTF-8"))){
							bufw.write(annotateURL(songURL) + songDoc.html());
							bufw.close();
						}
						System.out.println(Thread.currentThread().getName() + " saved song : " + songHref);
					}
				}
			} catch (Exception e) {
				System.err.println("Fetch error : " + pageurl);
				synchronized (this.artistList) {
					artistList.put(artistID, delay + 1000);
				}
				e.printStackTrace();
			    try {
			    	Thread.sleep(10000);
			    } catch (InterruptedException ei) {
			    	ei.printStackTrace();
			    }
			}
		}
		
		System.out.println(Thread.currentThread().getName() + " ends successfully!");
	}
	
	public String extractID(String href) {
		int firstIdx = href.lastIndexOf('/'), lastIdx = href.lastIndexOf('?');
		lastIdx = (lastIdx<0)?href.length():lastIdx;
		return href.substring(firstIdx+1, lastIdx);
	}
	
	public String annotateURL(String url){
		return "<!--" +  url + "-->\n";
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
	

	public void setProxy(String filepath)
	{
		HashMap<String,Integer> proxyMap = new HashMap<String,Integer>();
		BufferedReader bufr;
		try {
			bufr = new BufferedReader(new InputStreamReader(
					new FileInputStream(filepath), "UTF-8"));
			String line = null;
			while( (line = bufr.readLine())!=null ) {
				String[] tmps = line.split(":");
				proxyMap.put(tmps[0], Integer.parseInt(tmps[1]));
			}
			bufr.close();
			proxyList = new ArrayList<Map.Entry<String, Integer>>(proxyMap.entrySet());
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	public void setProvider(String filepath)
	{
		myproxyList = new ArrayList<MyProxy>();
		providerList = new ArrayList<DefaultCredentialsProvider>();
		BufferedReader bufr;
		try {
			bufr = new BufferedReader(new InputStreamReader(
					new FileInputStream(filepath), "UTF-8"));
			String line = null;
			while( (line = bufr.readLine())!=null ) {
				String[] tmps = line.split(",");
				DefaultCredentialsProvider scp = new DefaultCredentialsProvider();
				//61.160.221.41 888 tyt0308 tyt0308
				scp.addCredentials(tmps[2], tmps[3], tmps[0], Integer.parseInt(tmps[1]), null);
				providerList.add(scp);
				myproxyList.add(new MyProxy(line));
			}
			bufr.close();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	public String proxyConnect(String urlStr, int delay) throws Exception {
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
		            return IOUtils.toString(inPage, "utf-8");
				} else{
					Document pageDoc = Jsoup
							.connect(urlStr)
							.userAgent("Mozilla/5.0 (Windows; U; WindowsNT 5.1; en-US; rv2.0.1.6) Gecko/20140725 Firefox/2.0.0.6")
							.referrer("http://www.xiami.com").timeout(delay)
							.get();
					return pageDoc.html();
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
	
	public boolean mkdirs(String folder) {
		File f = new File(folder);
		return f.mkdirs();
	}
	
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
	
	static {
		System.setProperty("org.apache.commons.logging.Log", "org.apache.commons.logging.impl.NoOpLog");
//		java.util.logging.Logger.getLogger("").setLevel(Level.OFF); 
//		org.apache.log4j.Logger.getLogger("").setLevel(org.apache.log4j.Level.FATAL);     
	}
	      
	
	public static void main(String[] args) throws Exception {
		
		
		int maxThreads = 20;
		XiamiSongCrawler crawler = new XiamiSongCrawler("http://www.xiami.com/artist/", "xiami/artist.list", 1000L, "xiami/");
//		crawler.setProxy("config/proxy.txt");
		crawler.setProvider("config/provider.txt");
		ArrayList<Thread> threads = new ArrayList<Thread>();
		for (int i = 0; i < maxThreads; i++) {
			threads.add(new Thread(crawler,"Thread "+i));
		}
		System.out.println("Start crawling...");
		for (int i = 0; i < maxThreads; i++) {
			try {
				threads.get(i).start();
			} catch ( IllegalThreadStateException e) {
				e.printStackTrace();
				System.out.println(i);
				break;
			}
		}

	}

}
