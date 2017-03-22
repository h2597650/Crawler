package com.xiami.entity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import com.mysql.jdbc.exceptions.jdbc4.MySQLIntegrityConstraintViolationException;

public class Album implements SqlEntity{
	public long album_id, artist_id;
	public String str_id = "";
	public String name;
	public ArrayList<Long> tags = new ArrayList<Long>();
	public ArrayList<Long> genres = new ArrayList<Long>();
	public long play_cnt, collect_cnt, comments_cnt;
	public double score;
	public long rank5, rank4, rank3, rank2, rank1;
	public ArrayList<Song> songs = new ArrayList<Song>();
	
	@Override
	public boolean save2DB(Connection conn) {
		try {
			PreparedStatement stmt;
			// albums
			stmt = conn.prepareStatement("insert delayed into albums values(?,?,?,?,?,?,?,?,?,?,?,?,?)");
			stmt.setLong(1, album_id);
			stmt.setString(2, str_id);
			stmt.setString(3, name);
			stmt.setLong(4, play_cnt);
			stmt.setLong(5, collect_cnt);
			stmt.setLong(6, comments_cnt);
			stmt.setDouble(7, score);
			stmt.setDouble(8, rank5);
			stmt.setDouble(9, rank4);
			stmt.setDouble(10, rank3);
			stmt.setDouble(11, rank2);
			stmt.setDouble(12, rank1);
			stmt.setLong(13, artist_id);
			stmt.executeUpdate();
			stmt.close();
			// album_genre
			for(long genre_id : genres) {
				stmt = conn.prepareStatement("insert delayed into album_genre values(?,?)");
				stmt.setLong(1, album_id);
				stmt.setLong(2, genre_id);
				stmt.executeUpdate();
				stmt.close();
			}
			// album_tag
			for(long tag_id : tags) {
				stmt = conn.prepareStatement("insert delayed into album_tag values(?,?)");
				stmt.setLong(1, album_id);
				stmt.setLong(2, tag_id);
				stmt.executeUpdate();
				stmt.close();
			}
			stmt.close();
			// songs sql
			for(Song song : songs) {
				song.save2DB(conn);
			}
			return true;
		} catch (MySQLIntegrityConstraintViolationException e) {
            return false;
		} catch (SQLException e) {
			System.err.println(album_id + "," + str_id);
			e.printStackTrace();
			System.exit(-1);
			return false;
		}
	}
}
