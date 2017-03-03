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
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import org.json.JSONObject;
import org.json.JSONArray;



public class Crawler implements Runnable {

	private LinkedHashSet<String> toCrawlList;
	private HashSet<String> crawledList = new HashSet<String>();
	private LinkedList<SongInfo> songsList;
	
	private String baseUrl;
	private long startID, maxCnt, cnt;
	private String xmlFile;
	private Object writeLock, fetchLock;
	
	public Crawler(String baseUrl, long startID, long maxCnt, String xmlFile)
	{
		this.baseUrl = baseUrl;
		this.startID = startID;
		this.maxCnt = maxCnt;
		this.xmlFile = xmlFile;
		this.cnt = 0L;
		this.writeLock = new Object();
		this.fetchLock = new Object();
		toCrawlList = new LinkedHashSet<String>();
		songsList = new LinkedList<SongInfo>();
		toCrawlList.add("/song/" + startID);
		
		File file = new File(xmlFile);
		if(file.isFile() && file.exists())
			file.delete();
	}
	
	public void run(){
		crawl(baseUrl, startID, xmlFile);
	}
	
	public void crawl(String baseUrl, long startID, String filePath) 
	{
		if(!Thread.currentThread().getName().equals("Thread 0")) {
			try {
				Thread.sleep(2000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
		
		while(true)
		{
			String pageID;
			synchronized (this.toCrawlList)
			{
				if (toCrawlList.isEmpty())
					break;
				pageID = toCrawlList.iterator().next();
				toCrawlList.remove(pageID);
			}
			String pageurl = baseUrl + pageID;
			String pageContents = null;
			try {
				Document document = Jsoup.connect(pageurl)
						.userAgent("Mozilla/5.0 (Windows; U; WindowsNT 5.1; en-US; rv1.8.1.6) Gecko/20070725 Firefox/2.0.0.6")
						.referrer("http://www.baidu.com")
						.get();
				pageContents = document.html();
				if (pageContents != null && pageContents.length() > 0) {
					SongInfo songInfo = new SongInfo();
					if(songInfo.setValues(pageurl, pageContents))
					{
						synchronized (this.songsList)
						{
							cnt++;
							songsList.add(songInfo);
							synchronized (this.crawledList)
							{
								crawledList.add(pageID);
							}
							if(cnt%100==0)
								System.out.println("SongsCnt : " + cnt + ", toCrawlList : " + toCrawlList.size());
							if(songsList.size()%1000==0)
							{
								saveCurrentJSON(filePath);
								songsList.clear();
							}
							//System.out.println(Thread.currentThread().getName() + " saved: " + pageID);
						}
					}
					Elements songEles = document.select("span.song-title");
					ArrayList<String> hrefList = new ArrayList<String>();
					for(Element songEle : songEles) {
						String hrefStr = songEle.select("a").attr("href");
						if(!crawledList.contains(hrefStr))
							hrefList.add(hrefStr);
//						if(hrefStr.indexOf('?')>=0)
//							continue;
//						int lastIndex = hrefStr.lastIndexOf('/');
//						try {
//							long hrefID = Long.parseLong(hrefStr.substring(lastIndex+1));
//							hrefList.add(hrefID);
//						} catch (Exception e) {
//							System.err.println(pageID);
//							System.err.println(hrefStr);
//						}
					}
					synchronized (this.toCrawlList)
					{
						toCrawlList.addAll(hrefList);
					}
				}
			} catch (IOException e) {
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
				saveCurrentJSON(filePath);
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
	
	public void saveCurrentJSON(String path)
	{
		try {
			BufferedWriter bufw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(path,true), "UTF-8"));
			for(SongInfo info : songsList)
				bufw.write(info.toLineJSON() + "\n");
			bufw.close();
			songsList.clear();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	public void save2XML(String path)
	{
		try {
			BufferedWriter bufw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(path), "UTF-8"));
			bufw.write("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<songlist>\n");
			for(SongInfo info : songsList)
				bufw.write(info.toLineXML() + "\n");
			bufw.write("</songlist>\n");
			bufw.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	
	public static void main(String[] args) {
		int maxThreads = 20;
		Crawler crawler = new Crawler("http://music.baidu.com", 266322598L, 1000L, "songs.json");
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
