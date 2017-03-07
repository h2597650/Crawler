package com.xiami;

import java.util.*;
import java.util.Map.Entry;
import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.regex.*;

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
import com.gargoylesoftware.htmlunit.FailingHttpStatusCodeException;
import com.gargoylesoftware.htmlunit.IncorrectnessListener;
import com.gargoylesoftware.htmlunit.NicelyResynchronizingAjaxController;
import com.gargoylesoftware.htmlunit.ScriptException;
import com.gargoylesoftware.htmlunit.SilentCssErrorHandler;
import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.html.*;
import com.gargoylesoftware.htmlunit.javascript.JavaScriptErrorListener;
import com.gargoylesoftware.htmlunit.util.Cookie;



public class XiamiArtistCrawler implements Runnable {

	
	private LinkedHashMap<String, Integer> toCrawlList;
	private HashSet<String> crawledList;
	
	private String baseUrl, startID;
	private long maxCnt, cnt;
	private String basePath;
	private Object writeLock, fetchLock;
	
	private Map<String, String> userCookies;
	private CookieManager cookieManager;
	
	public XiamiArtistCrawler(String baseUrl, String startID, long maxCnt, String xmlFile)
	{
		this.baseUrl = baseUrl;
		this.startID = startID;
		this.maxCnt = maxCnt;
		this.basePath = xmlFile;
		this.cnt = 0L;
		this.writeLock = new Object();
		this.fetchLock = new Object();
		crawledList = new HashSet<String>();
		toCrawlList = new LinkedHashMap<String,Integer>();
		toCrawlList.put(startID, 0);
		
		File file = new File(xmlFile);
		if(file.isFile() && file.exists())
			file.delete();
	}
	
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
			if(delay>10000)
				continue;
			String pageurl = baseUrl + pageID;
            HtmlPage htmlPage = null;
			try {
				
				WebClient webClient=new WebClient(BrowserVersion.FIREFOX_45);
				webClient.setJavaScriptTimeout(10000);
				webClient.getOptions().setThrowExceptionOnScriptError(false);
				webClient.getOptions().setThrowExceptionOnFailingStatusCode(false);
				webClient.getOptions().setTimeout(10000);
				webClient.getOptions().setCssEnabled(false);
				webClient.getOptions().setJavaScriptEnabled(true);
				webClient.waitForBackgroundJavaScript(10000);
				webClient.setAjaxController(new NicelyResynchronizingAjaxController());
				//webClient.setCookieManager(cookieManager);

				
				
				htmlPage  = (HtmlPage) webClient.getPage(pageurl);
				// load js and ajax
				HtmlEmphasis playCnt_ele;
				do {
					playCnt_ele = (HtmlEmphasis)htmlPage.getBody().getElementsByAttribute("em", "id", "play_count_num").get(0);
					synchronized (htmlPage) {
						htmlPage.wait(500);
					}
				} while(playCnt_ele.getTextContent().length()==0);

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
				String folder = basePath + mkdirLevel(pageID);
				new File(folder).mkdirs();
				BufferedWriter bufw = new BufferedWriter(new OutputStreamWriter(
						new FileOutputStream(folder+"/"+pageID+".artist"), "UTF-8"));
				bufw.write(annotateURL(pageurl) + htmlPage.asXml());
				bufw.close();
				
				// extract album list
				String alubmurl = baseUrl + "album-" + pageID;
				Document albumMainPage = Jsoup.connect(alubmurl)
						.userAgent("Mozilla/5.0 (Windows; U; WindowsNT 5.1; en-US; rv2.0.1.6) Gecko/20140725 Firefox/2.0.0.6")
						.referrer("http://www.xiami.com")
						.timeout(5000+delay)
						.get();
				Element albumCntEle = albumMainPage.select("div.cate_viewmode").select(".clearfix >p.counts").first();
				int numsFirst=0, numsLast=0;
				for(int i = 0; i < albumCntEle.text().length(); ++i)
					if(Character.isDigit(albumCntEle.text().charAt(i))) {
						numsFirst = i;
						break;
					}
				for(int i = albumCntEle.text().length()-1; i >= 0; --i)
					if(Character.isDigit(albumCntEle.text().charAt(i))) {
						numsLast = i;
						break;
					}
				int albumCnt = Integer.parseInt(albumCntEle.text().substring(numsFirst, numsLast+1));
				int albumPageCnt = (albumCnt-1)/9+1;
				ArrayList<String> albumList = new ArrayList<String>();
				for(int idx = 1; idx <= albumPageCnt; ++idx)
				{
					Document albumsPage = Jsoup.connect(alubmurl + "?page=" + idx)
							.userAgent("Mozilla/5.0 (Windows; U; WindowsNT 5.1; en-US; rv1.5.1.6) Gecko/20070725 Firefox/2.0.0.6")
							.referrer("http://www.xiami.com")
							.timeout(5000+delay)
							.get();
					Elements albumEles = albumsPage.select("div.album_item100_thread > div.info a.CDcover100");
					
					for(Element albumEle : albumEles) {
						String albumHref = albumEle.attr("href");
						albumHref = extractID(albumHref);
						albumList.add(albumHref);
					}
				}
				//System.out.println(albumList.size());
				// save album pages
				for(String albumID : albumList)
				{
					String albumURL = "http://www.xiami.com/album/" + albumID;
					Document albumPage = Jsoup.connect(albumURL)
							.userAgent("Mozilla/5.0 (Windows; U; WindowsNT 5.1; en-US; rv2.1.1.6) Gecko/20090725 Firefox/2.0.0.6")
							.referrer("http://www.xiami.com")
							.timeout(5000+delay)
							.get();
					String albumFolder = folder + "/" + albumID;
					new File(albumFolder).mkdirs();
					bufw = new BufferedWriter(new OutputStreamWriter(
							new FileOutputStream(albumFolder + "/" + albumID + ".album"), "UTF-8"));
					bufw.write(annotateURL(albumURL) + albumPage.html());
					bufw.close();
					System.out.println(Thread.currentThread().getName() + " saved album : " + albumID);
					
					Elements songEles = albumPage.select("td.song_name");
					for(Element songEle : songEles) {
						String songHref = songEle.select("a").first().attr("href");
						songHref = extractID(songHref);
						String songURL = "http://www.xiami.com/song/" + songHref;
						Document songPage = Jsoup.connect(songURL)
								.userAgent("Mozilla/5.0 (Windows; U; WindowsNT 5.1; en-US; rv1.8.1.6) Gecko/20130725 Firefox/2.0.0.6")
								.referrer("http://www.xiami.com")
								.timeout(5000+delay)
								.get();
						bufw = new BufferedWriter(new OutputStreamWriter(
								new FileOutputStream(albumFolder + "/" + songHref + ".song"), "UTF-8"));
						bufw.write(annotateURL(songURL) + songPage.html());
						bufw.close();
						System.out.println(Thread.currentThread().getName() + " saved song : " + songHref);
					}
				}
				
				synchronized (this.crawledList)
		        {
					cnt++;
		        	crawledList.add(pageID);
		        	if(cnt%100==0)
						System.out.println("SongsCnt : " + cnt + ", toCrawlList : " + toCrawlList.size());
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
			}
		}
		if(Thread.currentThread().getName().equals("Thread 0"))
		{
			try {
				Thread.sleep(2000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			synchronized (this.writeLock) {
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
	
	static {
		System.setProperty("org.apache.commons.logging.Log", "org.apache.commons.logging.impl.NoOpLog");
//		java.util.logging.Logger.getLogger("").setLevel(Level.OFF); 
//		org.apache.log4j.Logger.getLogger("").setLevel(org.apache.log4j.Level.FATAL);     


	}
	      
	
	public static void main(String[] args) throws Exception {

//		java.util.logging.Logger.getLogger("").setLevel(Level.OFF); 
//		org.apache.log4j.Logger.getLogger("").setLevel(org.apache.log4j.Level.FATAL);    
//		org.apache.commons.logging.LogFactory.getFactory().setAttribute("org.apache.commons.logging.Log", "org.apache.commons.logging.impl.NoOpLog");
//			
//		org.apache.log4j.Logger.getRootLogger().setLevel(org.apache.log4j.Level.FATAL);
//		java.util.logging.Logger.getGlobal().setLevel(Level.OFF);
//		org.apache.log4j.Logger.getRootLogger().removeAllAppenders();
//		org.apache.log4j.Logger.getRootLogger().addAppender(new NullAppender());
		
//		org.apache.log4j.Logger.getLogger("com.gargoylesoftware.htmlunit").setLevel(org.apache.log4j.Level.FATAL); 
//		org.apache.log4j.Logger.getLogger("org.apache.commons.httpclient").setLevel(org.apache.log4j.Level.FATAL);
//		org.apache.log4j.Logger.getLogger("com.gargoylesoftware.htmlunit.javascript").setLevel(org.apache.log4j.Level.FATAL);
//		org.apache.log4j.Logger.getLogger("com.gargoylesoftware").setLevel(org.apache.log4j.Level.FATAL);
//		org.apache.log4j.Logger.getLogger("com.gargoylesoftware.htmlunit.javascript.background.JavaScriptJobManagerImpl").setLevel(org.apache.log4j.Level.FATAL);
//		org.apache.log4j.Logger.getLogger("com.gargoylesoftware.htmlunit.javascript.host.html.HTMLScriptElement").setLevel(org.apache.log4j.Level.FATAL);
//		org.apache.log4j.Logger.getLogger("org.apache.http").setLevel(org.apache.log4j.Level.FATAL);
//		org.apache.log4j.Logger.getLogger("org.apache.http.headers").setLevel(org.apache.log4j.Level.FATAL);
//		org.apache.log4j.Logger.getLogger("org.apache.http.wire").setLevel(org.apache.log4j.Level.FATAL);
//		org.apache.log4j.Logger.getLogger("org.apache.commons").setLevel(org.apache.log4j.Level.FATAL);
//		org.apache.log4j.Logger.getLogger("org.apache.commons.httpclient.wire").setLevel(org.apache.log4j.Level.FATAL);
//		org.apache.log4j.Logger.getLogger("gargoylesoftware.htmlunit.WebTestCase").setLevel(org.apache.log4j.Level.FATAL);
//		org.apache.log4j.Logger.getLogger("gargoylesoftware.htmlunit.javascript.DebugFrameImpl").setLevel(org.apache.log4j.Level.FATAL);	    
//		
  
//		org.apache.commons.logging.LogFactory.getFactory().setAttribute("org.apache.commons.logging.Log", "org.apache.commons.logging.impl.NoOpLog");	

//		java.util.logging.Logger.getLogger("org.mozilla").setLevel(Level.OFF); 
//		java.util.logging.Logger.getLogger("org.mozilla.javascript").setLevel(Level.OFF); 
//		java.util.logging.Logger.getLogger("sun.org.mozilla").setLevel(Level.OFF); 
//		java.util.logging.Logger.getLogger("sun.org.mozilla.javascript").setLevel(Level.OFF); 
//		
//		
//		java.util.logging.Logger.getLogger("com.gargoylesoftware.htmlunit").setLevel(Level.OFF); 
//		java.util.logging.Logger.getLogger("org.apache.commons.httpclient").setLevel(Level.OFF);
//		java.util.logging.Logger.getLogger("com.gargoylesoftware.htmlunit.javascript").setLevel(Level.OFF);
//		java.util.logging.Logger.getLogger("com.gargoylesoftware").setLevel(Level.OFF);
//		java.util.logging.Logger.getLogger("com.gargoylesoftware.htmlunit.javascript.background").setLevel(Level.OFF);
//		java.util.logging.Logger.getLogger("com.gargoylesoftware.htmlunit.javascript.host.html").setLevel(Level.OFF);
//		java.util.logging.Logger.getLogger("com.gargoylesoftware.htmlunit.javascript.host").setLevel(Level.OFF);
//		java.util.logging.Logger.getLogger("org.apache.http").setLevel(Level.OFF);
//		java.util.logging.Logger.getLogger("org.apache.http.headers").setLevel(Level.OFF);
//		java.util.logging.Logger.getLogger("org.apache.http.wire").setLevel(Level.OFF);
//		java.util.logging.Logger.getLogger("org.apache.commons").setLevel(Level.OFF);
//		java.util.logging.Logger.getLogger("org.apache.commons.httpclient.wire").setLevel(Level.OFF);
//		java.util.logging.Logger.getLogger("gargoylesoftware.htmlunit.WebTestCase").setLevel(Level.OFF);
//		java.util.logging.Logger.getLogger("gargoylesoftware.htmlunit.javascript.DebugFrameImpl").setLevel(Level.OFF);
//




		
		
		int maxThreads = 2;
		String[] artistIDs = new String[]{"dz264c5b", "ihyffebb", "6in9397a", "0d5492a", "iim17edb", "djGc4149", 
				"O9fc383", "573", "2017", "135", "57908", "bhu21804", "hf0143fd", "sGE39222", "K1z4dbb3", 
				"1198", "fHIe070b", "3110", "xIW446b0", "Ksd4688", "9K9c05a", "198ed78", "vuV4030c", "be6yda0f8", 
				"dfTeba0e", "doO83b43", "iuzfce26", "cVv60212", "cUW5e566", "gaWaf903", "4q557b4", "c9b4b17c", 
				"ixyea99c", "iwbf4790", "fGr9f340", "wz90661"};
		XiamiArtistCrawler crawler = new XiamiArtistCrawler("http://www.xiami.com/artist/", artistIDs, 1000L, "xiami/");
		crawler.login("15010700399","2517399LK");
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
