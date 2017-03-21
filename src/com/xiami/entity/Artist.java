package com.xiami.entity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;

public class Artist implements SqlEntity{
	public long artist_id;
	public String str_id = "";
	public String name;
	public ArrayList<Long> tags = new ArrayList<Long>();
	public ArrayList<Long> genres = new ArrayList<Long>();
	public long play_cnt, fans_cnt, comments_cnt;
	public ArrayList<Album> albums = new ArrayList<Album>();
	public ArrayList<String> similars = new ArrayList<String>();
	
	@Override
	public boolean save2DB(Connection conn) {
		try {
			PreparedStatement stmt;
			stmt = conn.prepareStatement("select artist_id from artists where artist_id = ?");
			stmt.setLong(1, artist_id);
			ResultSet rs = stmt.executeQuery();
			if (rs.next()) {
				stmt.close();
				return true;
			}
			stmt.close();
			// artists
			stmt = conn.prepareStatement("insert delayed into artists values(?,?,?,?,?,?)");
			stmt.setLong(1, artist_id);
			stmt.setString(2, str_id);
			stmt.setString(3, name);
			stmt.setLong(4, play_cnt);
			stmt.setLong(5, fans_cnt);
			stmt.setLong(6, comments_cnt);
			stmt.executeUpdate();
			stmt.close();
			// artist_tag
			for(long tag_id : tags) {
				stmt = conn.prepareStatement("replace delayed into artist_tag values(?,?)");
				stmt.setLong(1, artist_id);
				stmt.setLong(2, tag_id);
				stmt.executeUpdate();
				stmt.close();
			}
			// artist_genre
			for(long genre_id : genres) {
				stmt = conn.prepareStatement("replace delayed into artist_genre values(?,?)");
				stmt.setLong(1, artist_id);
				stmt.setLong(2, genre_id);
				stmt.executeUpdate();
				stmt.close();
			}
			stmt.close();
			// albums sql
			for(Album album : albums) {
				album.save2DB(conn);
			}
			return true;
		} catch (SQLException e) {
			e.printStackTrace();
			return false;
		}
	}
	
	
}
