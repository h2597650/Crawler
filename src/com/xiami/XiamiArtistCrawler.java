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



public class XiamiArtistCrawler implements Runnable {

	
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
	
//	public XiamiArtistCrawler(String baseUrl, String startID, long maxCnt, String xmlFile)
//	{
//		this.baseUrl = baseUrl;
//		this.startID = startID;
//		this.maxCnt = maxCnt;
//		this.basePath = xmlFile;
//		this.cnt = 0L;
//		this.writeLock = new Object();
//		this.fetchLock = new Object();
//		crawledList = new HashSet<String>();
//		toCrawlList = new LinkedHashMap<String,Integer>();
//		toCrawlList.put(startID, 0);
//		
//		
//		File file = new File(xmlFile);
//		if(file.isFile() && file.exists())
//			file.delete();
//	}
	
	public XiamiArtistCrawler(String baseUrl, String[] startIDs, long maxCnt, String xmlFile)
	{
		this.baseUrl = baseUrl;
		this.startID = startIDs[0];
		this.maxCnt = maxCnt;
		this.basePath = xmlFile;
		this.cnt = 0L;
		this.writeLock = new Object();
		this.fetchLock = new Object();
		crawledList = new HashSet<String>();
		toCrawlList = new LinkedHashMap<String,Integer>();
		for(String id : startIDs)
			toCrawlList.put(id, 0);
		artistList = new LinkedHashMap<String,Integer>();
		
		File file = new File(xmlFile);
		if(file.isFile() && file.exists())
			file.delete();
	}
	
	public void run(){
		crawl(baseUrl, startID, basePath);
	}
	
	public void crawl(String baseUrl, String startID, String filePath) 
	{
		if(!Thread.currentThread().getName().equals("Thread 0")) {
			try {
				Thread.sleep(4000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
		
		while(true)
		{
			String pageID;
			int delay;
			synchronized (this.toCrawlList)
			{
				if (toCrawlList.isEmpty())
					break;
				Map.Entry<String, Integer> idEntry = toCrawlList.entrySet().iterator().next();
				pageID = idEntry.getKey();
				delay = idEntry.getValue();
				toCrawlList.remove(pageID);
			}
			synchronized (this.crawledList) {
				if (crawledList.contains(pageID))
					continue;
			}
			if(delay>10000)
				continue;
			String pageurl = baseUrl + pageID;
            HtmlPage htmlPage = null;
			try {
				
				WebClient webClient=new WebClient(BrowserVersion.FIREFOX_45);
				webClient.setJavaScriptTimeout(10000+delay);
				webClient.getOptions().setThrowExceptionOnScriptError(false);
				webClient.getOptions().setThrowExceptionOnFailingStatusCode(false);
				webClient.getOptions().setTimeout(10000+delay);
				webClient.getOptions().setCssEnabled(false);
				webClient.getOptions().setJavaScriptEnabled(true);
				webClient.waitForBackgroundJavaScript(10000+delay);
				webClient.setAjaxController(new NicelyResynchronizingAjaxController());
//				if(proxyList!=null && proxyList.size()>0) {
//					int idx = Math.abs(pageID.hashCode()) % proxyList.size();
//					ProxyConfig proxyConfig = new ProxyConfig(proxyList.get(idx).getKey(), proxyList.get(idx).getValue());
//					webClient.getOptions().setProxyConfig(proxyConfig);
//				}
				//webClient.setCookieManager(cookieManager);
				if(providerList!=null && providerList.size()>0) {
					int idx = Math.abs((pageID+System.currentTimeMillis()).hashCode()) % providerList.size();
					webClient.setCredentialsProvider(providerList.get(idx));
				}

				
				htmlPage  = (HtmlPage) webClient.getPage(pageurl);
				// load js and ajax
				HtmlEmphasis playCnt_ele;
				for(int i = 0; i < 20; ++i) {
					playCnt_ele = (HtmlEmphasis)htmlPage.getBody().getElementsByAttribute("em", "id", "play_count_num").get(0);
                    if (playCnt_ele.getTextContent().length()>0)
                        break;
					synchronized (htmlPage) {
						htmlPage.wait(500);
					}
                }
				// parse two types
				//http://www.xiami.com/artist/dz264c5b
				HashSet<String> hrefSet_tmp = new HashSet<String>(), hrefSet = new HashSet<String>();
				HtmlElement recommendData = (HtmlElement) htmlPage.getElementById("artist_recommend");
				if(recommendData != null) {
					List<HtmlElement> recommendItems = recommendData.getElementsByTagName("a");
					for(HtmlElement item : recommendItems) {
						String href = ((HtmlAnchor)item).getHrefAttribute();
						if(href.contains("id"))
							continue;
						href = extractID(href);
						hrefSet_tmp.add(href);
					}
				}
				//http://i.xiami.com/zhangzhenyue
				HtmlElement likeData = (HtmlElement) htmlPage.getElementById("artist_like");
				if(likeData != null)
				{
					List<HtmlElement> likeItems = likeData.getElementsByAttribute("div", "class", "artist_like_item");
					for(HtmlElement item : likeItems) {
						HtmlAnchor node = (HtmlAnchor)item.getFirstByXPath("a");
						String href = node.getHrefAttribute();
						href = extractID(href);
						hrefSet_tmp.add(href);
					}
				}
				webClient.close();
				// save page
				String folder = basePath + mkdirLevel(pageID) + "/" + pageID;
				mkdirs(folder);
				try (BufferedWriter bufw = new BufferedWriter(new OutputStreamWriter(
						new FileOutputStream(folder + "/" + pageID+".artist"), "UTF-8"))){
					bufw.write(annotateURL(pageurl) + htmlPage.asXml());
					bufw.close();
				}
				
				
				synchronized (this.crawledList)
		        {
					cnt++;
		        	crawledList.add(pageID);
		        	if(cnt%100==0)
						System.out.println("ArtistsCnt : " + cnt +", toCrawlList : " + toCrawlList.size());
		        	System.out.println(Thread.currentThread().getName() + " saved artist : " + pageID);
		        }
				synchronized (this.crawledList)
				{
					for(String val : hrefSet_tmp)
						if(!crawledList.contains(val))
							hrefSet.add(val);
							
				}
				synchronized (this.toCrawlList)
				{
					for(String key : hrefSet)
						if(!toCrawlList.containsKey(key))
							toCrawlList.put(key, 0);
				}

			} catch (Exception e) {
                System.err.println("Fetch error : " + pageurl);
				synchronized (this.toCrawlList)
				{
					toCrawlList.put(pageID, delay+1000);
				}
				e.printStackTrace();
		        //System.out.println(htmlPage.asXml());
			    try {
			    	Thread.sleep(10000);
			    } catch (InterruptedException ei) {
			    	ei.printStackTrace();
			    }
			}
		}
		if(Thread.currentThread().getName().equals("Thread 0"))
		{
			try {
				Thread.sleep(40000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			synchronized (this.writeLock) {
				for(String artist: crawledList)
					artistList.put(artist, 0);
				System.out.println(Thread.currentThread().getName() + " said : ready to crawl songs!");
				this.writeLock.notifyAll();
			}
		}
		
		try {
			synchronized (this.writeLock) {
				if(!Thread.currentThread().getName().equals("Thread 0"))
					this.writeLock.wait();
			}
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		
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
	
	public void login(String account, String password) throws Exception {
		Map<String, String> loginPageCookies = Jsoup.connect("https://login.xiami.com/member/login")
	            .method(Connection.Method.POST).execute().cookies();

	    Connection.Response loginResponse = Jsoup.connect("https://login.xiami.com/member/login")
	            .data("mode", "login", "account", account, "pw", password)
	            .cookies(loginPageCookies)
	            .method(Connection.Method.POST).execute();

	    userCookies = loginResponse.cookies();
	    Jsoup.connect("http://www.xiami.com/artist/album-3110?page=5").cookies(userCookies).get().body();
	    //System.out.println(Jsoup.connect("http://www.xiami.com/artist/album-3110?page=5").cookies(userCookies).get().body());
	    
	    cookieManager = new CookieManager();
	    for(Map.Entry<String, String> entry : loginPageCookies.entrySet())
			cookieManager.addCookie(new Cookie("http://www.xiami.com", entry.getKey(), entry.getValue()));


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
		String[] artistIDs = new String[]{"dz264c5b", "ihyffebb", "6in9397a", "0d5492a", "iim17edb", "djGc4149", 
				"O9fc383", "573", "2017", "135", "57908", "bhu21804", "hf0143fd", "sGE39222", "K1z4dbb3", 
				"1198", "fHIe070b", "3110", "xIW446b0", "Ksd4688", "9K9c05a", "198ed78", "vuV4030c", "be6yda0f8", 
				"dfTeba0e", "doO83b43", "iuzfce26", "cVv60212", "cUW5e566", "gaWaf903", "4q557b4", "c9b4b17c", 
				"ixyea99c", "iwbf4790", "fGr9f340", "wz90661", "nb9Qfda9e", "nfZH1d73a", "1536810071", "7V3S1F1747c", 
                "bifmb0a75", "CmK5492f9", "b08gREF3d702", "eFiI759ab", "dpew58809", "32414", "OEJ8d7cf", "bxeced2e2",
                "iTnwc9824", "eHPra3049", "di8q3a59d", "32494", "rrc121d0a", "924056356", "4OZ51f7c", "rvD33b8b",
                "dvX537b7e", "d4sE42b48", "yhDzyIad0f8", "eF5Q888dd", "iO0Pd6d61", "cV642fbeb", "mTlUKB7cdc9",
                "UH9ddf74", "bkora23aa", "mhSfQb4d7f9", "Co0y45303", "cR752dc28", "yhDubac11f4", "3Ok7e759",
                "bYPQ8ydaa27", "2100015558", "eGb14e269", "eHG469717", "x0Gsyyc5e8c", "bnvvbe4b2", "dI2242bd0",
                "Z6Habad8", "wWmI9z99d19", "iQKpa1012", "b9EBfba81", "ciRl29bab", "bsYOebe4e", "eCOL60597",
                "cT3d09c0", "ncAAe290a", "bdpvcad80", "wW71YZd5480", "ETI5c115", "eFAo7be88", "bvB3f29c5",
                "PNK51ab9", "2100015572", "beZLdee43", "Wrt1617b", "2100015574", "bfNt14f43", "cxfSM5b7af0",
                "9cy1cg19adc", "c1BD27b72", "Ndk63057", "blmqe0a79", "nl9yjA5ca43", "yhDyBkb0da9", "3AJ7301e",
                "bF2N1b707", "roml20c2c", "3QIx3be6d", "bn8Zce270", "bFM1f5949", "90721953", "bu10ff670", "Ju77dff9",
                "bbdbbd00a", "xIbotsc5908", "TFnced17", "eHkz6f6d0", "Wt8d5841", "zEYJ2dc0b", "bnF0cd8fa", "gN8pAD2e402",
                "eDQn7bcab", "b99Wea3ad", "hTsZJA58935", "pacIsd535b", "fEvd6811", "iTqG125fe", "yhRutl12cdf", "b1tDfd939",
                "cvDR2ac2d", "yhs81Gcba0a", "XUId575a", "8x0920c3", "bf1Da425f", "Z9Cc80d0", "bc6db6b12"};
		XiamiArtistCrawler crawler = new XiamiArtistCrawler("http://www.xiami.com/artist/", artistIDs, 1000L, "xiami/");
//		crawler.login("15010700399","2517399LK");
//		crawler.setProxy("proxy.txt");
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
