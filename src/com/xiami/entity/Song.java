package com.xiami.entity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;

public class Song implements SqlEntity{
	public long song_id, album_id, artist_id;
	public String str_id = "";
	public String name;
	public ArrayList<Long> tags = new ArrayList<Long>();
	public long play_cnt, share_cnt, comments_cnt;
	
	@Override
	public boolean save2DB(Connection conn) {
		try {
			PreparedStatement stmt;
			// songs
			stmt = conn.prepareStatement("replace into songs values(?,?,?,?,?,?,?)");
			stmt.setLong(1, song_id);
			stmt.setString(2, str_id);
			stmt.setString(3, name);
			stmt.setLong(4, play_cnt);
			stmt.setLong(5, share_cnt);
			stmt.setLong(6, comments_cnt);
			stmt.setLong(7, album_id);
			//stmt.setLong(8, artist_id);
			stmt.executeUpdate();
			stmt.close();
			// song_artist
			stmt = conn.prepareStatement("replace into song_artist values(?,?)");
			stmt.setLong(1, song_id);
			stmt.setLong(2, artist_id);
			stmt.executeUpdate();
			stmt.close();
			// song_tag
			for(long tag_id : tags) {
				stmt = conn.prepareStatement("replace into song_tag values(?,?)");
				stmt.setLong(1, song_id);
				stmt.setLong(2, tag_id);
				stmt.executeUpdate();
				stmt.close();
			}
			stmt.close();
			return true;
		} catch (SQLException e) {
			System.err.println(song_id + "," + str_id);
			e.printStackTrace();
			System.exit(-1);
			return false;
		}
	}
}
