package com.xiami;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.sql.*;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.concurrent.CountDownLatch;

import org.apache.commons.io.IOUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import com.xiami.entity.Artist;
import com.xiami.entity.PageParser;

public class XiamiMysql {

	private Connection conn = null;
	private String baseFolder;
	//private PageParser parser;
	private ArrayList<Artist> artistList = new  ArrayList<Artist>();
	private LinkedList<String> fileList = new LinkedList<String>();
	
	public XiamiMysql() {
	}
	
	public boolean excuteSQL(String sqlPath) {
		Statement stmt = null;
		System.out.println("Excuting SQL...");
		try {
			stmt = conn.createStatement();
			InputStream sqlStream = new FileInputStream(sqlPath);
			String fileString = IOUtils.toString(sqlStream, "utf-8");
			for(String sqlString : fileString.split(";")) {
				sqlString = sqlString.trim();
				if (sqlString.length()>0)
					stmt.executeUpdate(sqlString);
			}
			stmt.close();
			System.out.println("Excute SQL completed!");
		} catch (Exception e) {
			e.printStackTrace();
		}
		return true;
	}
	
	

	
	public void traverseFolder(String folderName) {
		File folder = new File(folderName);
		if (!folder.isDirectory())
			return;
		File[] files = folder.listFiles();
		boolean flag = true;
		for(File file : files) {
			if(file.getName().contains(".artist")) {
				fileList.add(file.getPath());
				flag = false;
				break;
			}
		}
		if (flag) {
			for(File file : files)
				traverseFolder(file.getPath());
		}
	}
	
	private boolean hasAlpha(String s) {
		for(int i = 0; i < s.length(); ++i)
			if (!Character.isDigit(s.charAt(i)))
				return true;
		return false;
	}
	
	public boolean saveSimilarArtist() {
		System.out.println("Reload similar artists......");
		//HashMap<Long, String> id2strMap = new HashMap<Long, String>();
		HashMap<String, Long> str2idMap = new HashMap<String, Long>();
		try {
			PreparedStatement stmt;
			stmt = conn.prepareStatement("select artist_id,str_id from artists");
			ResultSet rs = stmt.executeQuery();
			while(rs.next()) {
				long artist_id = rs.getLong("artist_id");
				String str_id = rs.getString("str_id");
				//id2strMap.put(artist_id, str_id);
				//System.out.println(artist_id + "," + str_id);
				str2idMap.put(str_id, artist_id);
			}
			stmt.close();
			for(Artist artist : artistList) {
				for(String href : artist.similars) {
					long similar_id;
					if(!hasAlpha(href))
						similar_id = Long.parseLong(href);
					else {
						if(!str2idMap.containsKey(href))
							continue;
						similar_id = str2idMap.get(href);
					}
					stmt = conn.prepareStatement("replace into artist_similar values (?,?)");
					stmt.setLong(1, artist.artist_id);
					stmt.setLong(2, similar_id);
					stmt.executeUpdate();
					stmt.close();
				}
			}
			System.out.println("Load similar artists completed!");
			return true;
		} catch (SQLException e) {
			e.printStackTrace();
			return false;
		}
	}
	class Test {
		public void print() {
			hasAlpha("aaaa");
		}
	}
	public static void main(String[] args) throws Exception {
		// TODO decode str to url
		String str = "http://www.xiami.com/song/playlist/id/2100337262/type/1/cat/json";
		String urlStr = java.net.URLDecoder.decode(str, "utf-8").replace('^', '0');
		URL url = new URL(urlStr);
		URLConnection conn = url.openConnection();
        conn.setConnectTimeout(500);
        InputStream inPage = conn.getInputStream();
        try (FileOutputStream out = new FileOutputStream("haha.mp3")) {
            IOUtils.copy(inPage, out);
        }
		System.exit(0);
		
		int maxThreads = 20;
		XiamiMysql xiamiMysql = new XiamiMysql();
		System.out.println("Start collecting fileName..");
		xiamiMysql.traverseFolder("xiami");
		// multi threads
		PageParser parser = new PageParser(xiamiMysql.artistList, xiamiMysql.fileList);
		
		xiamiMysql.conn = parser.connectJDBC("root", "root");
		xiamiMysql.excuteSQL("config/create.sql");
		ArrayList<Thread> threads = new ArrayList<Thread>();
		for (int i = 0; i < maxThreads; i++) {
			threads.add(new Thread(parser,"Thread "+i));
		}
		System.out.println("Start parsing...");
		try {
			for (int i = 0; i < maxThreads; i++) 
				threads.get(i).start();
			for (int i = 0; i < maxThreads; i++) 
				threads.get(i).join();
		} catch ( Exception e) {
			e.printStackTrace();
		}
		xiamiMysql.saveSimilarArtist();
		xiamiMysql.excuteSQL("config/alter_index.sql");
	}

}
