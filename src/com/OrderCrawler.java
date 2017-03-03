package com;

import java.util.*;
import java.net.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.*;

import org.jsoup.Connection;
import org.jsoup.Connection.Response;
import org.jsoup.Jsoup;
import org.jsoup.helper.HttpConnection;

import java.net.HttpURLConnection;


public class OrderCrawler implements Runnable {

	private AtomicLong pageID;
	private LinkedList<SongInfo> songsList;
	
	private String baseUrl;
	private long startID, endID;
	private String xmlFile;
	
	public OrderCrawler(String baseUrl, long startID, long endID, String xmlFile)
	{
		this.baseUrl = baseUrl;
		this.startID = startID;
		this.endID = endID;
		this.xmlFile = xmlFile;
	}
	
	public void run(){
		crawl(baseUrl, startID, endID);
	}
	
	public void crawl(String baseUrl, long startID, long endID) 
	{
		
		pageID = new AtomicLong(startID);
		songsList = new LinkedList<SongInfo>();
		while(pageID.get() < endID)
		{
//			URL pageurl = null;
//			try {
//				pageurl = new URL(baseUrl + pageID);
//			} catch (MalformedURLException e) {
//				e.printStackTrace();
//			}
			//String pageContents = downloadPage(pageurl);
			String pageurl = baseUrl + pageID;
			String pageContents = null;
			try {
				pageContents = Jsoup.connect(pageurl).
						userAgent("Mozilla/5.0 (Windows; U; WindowsNT 5.1; en-US; rv1.8.1.6) Gecko/20070725 Firefox/2.0.0.6").
						referrer("http://www.baidu.com")
						.get().html();
				if (pageContents != null && pageContents.length() > 0) {
					SongInfo songInfo = new SongInfo();
					if(songInfo.setValues(pageurl, pageContents))
					{
						synchronized (this.songsList)
						{
							songsList.add(songInfo);
							System.out.println(Thread.currentThread().getName() + " saved: " + pageID);
						}
					}
				}
			} catch (IOException e) {
				//e.printStackTrace();
			}
			pageID.incrementAndGet();
		}
		try{
			Thread.sleep(2000);
		} catch (Exception e) {
			e.printStackTrace();
		}
		System.out.println(Thread.currentThread().getName() + " ends successfully!");
	}
	
	
	//phone page?
	private String downloadPage(URL pageUrl) {
		try {
			// Open connection to URL for reading.
			BufferedReader reader = new BufferedReader(new InputStreamReader(
					pageUrl.openConnection().getInputStream(),"utf-8"));

			// Read page into buffer.
			String line;
			StringBuffer pageBuffer = new StringBuffer();
			while ((line = reader.readLine()) != null) {
				pageBuffer.append(line+'\n');
			}

			return pageBuffer.toString();
		} catch (Exception e) {
		}

		return null;
	}
	
	public static void main(String[] args) {
		int maxThreads = 40;
		OrderCrawler crawler = new OrderCrawler("http://music.baidu.com/song/", 238665630L, 248665639L, "songs.xml");
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
