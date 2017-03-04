package com.xiami;

import java.util.*;
import java.util.Map.Entry;
import java.net.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.*;

import org.apache.commons.logging.LogFactory;
import org.jsoup.Connection;
import org.jsoup.Connection.Response;
import org.jsoup.Jsoup;
import org.jsoup.helper.HttpConnection;
import org.jsoup.helper.StringUtil;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.json.JSONObject;
import org.json.JSONArray;

import com.baidu.SongInfo;
import com.gargoylesoftware.htmlunit.BrowserVersion;
import com.gargoylesoftware.htmlunit.FailingHttpStatusCodeException;
import com.gargoylesoftware.htmlunit.NicelyResynchronizingAjaxController;
import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.html.*;



public class XiamiArtistCrawler implements Runnable {

	
	private LinkedHashMap<String, Integer> toCrawlList;
	private HashSet<String> crawledList;
	private LinkedList<SongInfo> songsList;
	
	private String baseUrl, startID;
	private long maxCnt, cnt;
	private String basePath;
	private Object writeLock, fetchLock;
	
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
		songsList = new LinkedList<SongInfo>();
		toCrawlList.put(startID, 0);
		
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
				Thread.sleep(40000);
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
			String pageContents = null;
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
				
				HtmlPage htmlPage  = (HtmlPage) webClient.getPage(pageurl);
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
						int firstIdx = href.lastIndexOf('/'), lastIdx = href.lastIndexOf('?');
						lastIdx = (lastIdx<0)?href.length():lastIdx;
						href = href.substring(firstIdx+1, lastIdx);
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
						int firstIdx = href.lastIndexOf('/'), lastIdx = href.lastIndexOf('?');
						lastIdx = (lastIdx<0)?href.length():lastIdx;
						href = href.substring(firstIdx+1, lastIdx);
						hrefSet_tmp.add(href);
					}
				}
				webClient.close();
				String folder = basePath + mkdirLevel(pageID);
				new File(folder).mkdirs();
				BufferedWriter bufw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(folder+"/"+pageID), "UTF-8"));
				bufw.write(htmlPage.asXml());
				bufw.close();
				
				synchronized (this.crawledList)
		        {
					cnt++;
		        	crawledList.add(pageID);
		        	if(cnt%100==0)
						System.out.println("SongsCnt : " + cnt + ", toCrawlList : " + toCrawlList.size());
		        	//System.out.println(Thread.currentThread().getName() + " saved: " + pageID);
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
				//e.printStackTrace();
			}
		}
		if(Thread.currentThread().getName().equals("Thread 0"))
		{
			try {
				Thread.sleep(2000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			synchronized (this.songsList){
				//saveCurrentJSON(filePath);
				synchronized (this.writeLock) {
					this.writeLock.notifyAll();
				}
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
	
	public String mkdirLevel(String name){
		ArrayList<String> dirs = new ArrayList<String>();
		int interval = 2, max_interval = 2;
		for(int i = 0; i < max_interval; ++i) {
			String subDir = name.substring(interval*i, Math.min(interval*(i+1), name.length()));
			dirs.add(subDir);
		}
		return StringUtil.join(dirs, "/");
	}
	
	public static void main(String[] args) throws Exception {

		LogFactory.getFactory().setAttribute("org.apache.commons.logging.Log", "org.apache.commons.logging.impl.NoOpLog"); 
		int maxThreads = 24;
		XiamiArtistCrawler crawler = new XiamiArtistCrawler("http://www.xiami.com/artist/", "dz264c5b", 1000L, "xiami/artists/");
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
