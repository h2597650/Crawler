package com.xiami;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;

import org.apache.commons.io.IOUtils;

import com.xiami.entity.Artist;
import com.xiami.entity.PageParser;

public class XiamiMysql {

	private Connection conn = null;
	private String baseFolder, sqlPath;
	//private PageParser parser;
	private ArrayList<Artist> artistList = new  ArrayList<Artist>();
	
	public XiamiMysql(String user, String password, String sqlPath) {
		connectJDBC(user, password);
		this.sqlPath = sqlPath;
	}
	
	public boolean createDataBase() {
		Statement stmt = null;
		try {
			stmt = conn.createStatement();
			InputStream sqlStream = new FileInputStream(sqlPath);
			String fileString = IOUtils.toString(sqlStream, "utf-8");
			for(String sqlString : fileString.split(";")) {
				sqlString = sqlString.trim();
				if (sqlString.length()>0)
					stmt.executeUpdate(sqlString);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return true;
	}
	
	private boolean connectJDBC(String user, String password){
		String DBDRIVER = "com.mysql.jdbc.Driver";    
	    try {
			Class.forName(DBDRIVER);
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
		}
	    
	    String url="jdbc:mysql://localhost:3306/xiami";   
        try {
            conn = DriverManager.getConnection(url, user, password);
            Statement stmt = conn.createStatement();
            stmt.close();
            return true;
        } catch (SQLException e){
            e.printStackTrace();
            return false;
        } 
	}

	
	public void traverseFolder(String folderName) {
		File folder = new File(folderName);
		if (!folder.isDirectory())
			return;
		File[] files = folder.listFiles();
		boolean flag = true;
		for(File file : files) {
			if(file.getName().contains(".artist")) {
				PageParser parser = new PageParser(conn, artistList, file.getPath());
				while(PageParser.count.get()>20)
					try {
						Thread.sleep(100);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				Thread parserThread = new Thread(parser);
				parserThread.start();
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
					stmt = conn.prepareStatement("replace into artist_simlar values (?,?)");
					stmt.setLong(1, artist.artist_id);
					stmt.setLong(1, similar_id);
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
	
	public static void main(String[] args) {
		XiamiMysql xiamiMysql = new XiamiMysql("root", "root", "config/create.sql");
		xiamiMysql.createDataBase();
		xiamiMysql.traverseFolder("xiami");
		//xiamiMysql.saveSimilarArtist();
	}

}
