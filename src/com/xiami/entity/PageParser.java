package com.xiami.entity;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.io.IOUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.jsoup.nodes.Element;

public class PageParser implements Runnable {
	private Connection conn = null;
	private ArrayList<Artist> artistList;
	private LinkedList<String> fileList;
	
	public PageParser(Connection conn, ArrayList<Artist> artistList, LinkedList<String> fileList) {
		this.conn = conn;
		this.artistList = artistList;
		this.fileList = fileList;
	}
	
	public void run() {
		while(true) {
			String filePath;
			synchronized (this.fileList) {
				if(fileList.isEmpty())
					break;
				if(fileList.size()%100==0)
					System.out.println("To Parse cnt : " + fileList.size() + "-------------------------");
				filePath = fileList.getFirst();
				fileList.removeFirst();
			}
			Artist artist = parseArtist(filePath);
			synchronized (this.artistList) {
				artistList.add(artist);
				System.out.println(Thread.currentThread().getName() + " parsed artist : " + artist.artist_id + ", " +artist.name);
			}
		}
	}
	
	public Artist parseArtist(String filePath) {
		Document artistDoc = Jsoup.parse(fileContent(filePath));
		Artist artist = new Artist();
		// similar artist
		Elements recommendEles = artistDoc.select("div#artist_recommend");
		Elements likeEles = artistDoc.select("div#artist_like");
		Elements tagEles = null, hrefEles = null;
		if (recommendEles!=null && recommendEles.size()>0) {
			hrefEles = recommendEles.first().select("div>ul>li>a");
			// TODO name tags
			artist.name = artistDoc.select("div#title>h1").first().ownText();
			tagEles = artistDoc.select("div#artist_tags_block>div.content").select(".clearfix>a");
		}
		if (likeEles!=null && likeEles.size()>0) {
			hrefEles = likeEles.first().select("div.artist_like_item>a");
			// name tags
			artist.name = artistDoc.select("div#glory-title div.clearfix>h1").first().ownText();
			tagEles = artistDoc.select("div#artist_tag>div.content").select(".clearfix>a");
		}
		for(Element hrefEle : hrefEles) {
			String hrefID = extractID(hrefEle.attr("href"));
			artist.similars.add(hrefID);
		}
		// tags
		for(Element tagEle : tagEles) {
			String tagIDstr = tagEle.attr("id");
			if (tagIDstr.lastIndexOf('_')==tagIDstr.length()-1)
				continue;
			long tagID = Long.parseLong(tagIDstr.substring(tagIDstr.lastIndexOf('_')+1));
			String tagContent = tagEle.text();
			if (tagContent.length()>20)
				continue;
			insertTag(tagID, tagContent);
			artist.tags.add(tagID);
		}
		// id
		Element idEle = artistDoc.select("div#qrcode a.qrcode>img").first();
		artist.artist_id = Long.parseLong(extractID(idEle.attr("src")));
		// genres
		Element infoEle = artistDoc.select("div#artist_info").first();
		Elements genreEles = infoEle.select("a[class!=more]");
		for(Element genreEle : genreEles) {
			String genreHref = genreEle.attr("href");
			long genreID = Long.parseLong(extractID(genreHref));
			String genreContent = genreEle.text();
			insertGenre(genreID, genreContent);
			artist.genres.add(genreID);
		}
		// cnts
		Elements cntEles = artistDoc.select("div.music_counts>ul.clearfix>li");
		artist.play_cnt = Long.parseLong(cntEles.get(0).select("em#play_count_num").text());
		artist.fans_cnt = Long.parseLong(cntEles.get(1).select("a").first().ownText());
		artist.comments_cnt = Long.parseLong(cntEles.get(2).select("a[href=#wall]").first().ownText());
		
		// add albums
		// str_id, fetch from album
		artist.str_id = "" + artist.artist_id;
		File artistFolderFile = new File(filePath).getParentFile();
		File[] albumFiles = artistFolderFile.listFiles();
		for(File albumFolder : albumFiles) {
			if (albumFolder.isDirectory()) {
				String albumFileName = albumFolder.getPath() + "/" + albumFolder.getName() + ".album";
				Album album = parseAlbum(albumFileName, artist);
				artist.albums.add(album);
			}
		}
		//synchronized (this.conn) {
			artist.save2DB(conn);
		//}
		artist.albums.clear();
		return artist;
	}
	
	public Album parseAlbum(String filePath, Artist artist) {
		Document albumDoc = Jsoup.parse(fileContent(filePath));
		Album album = new Album();
		album.artist_id = artist.artist_id;
		// id
		Element createCommentEle = albumDoc.select("div.creat_comment > a.bt_links").first();
		album.album_id = Long.parseLong(extractID(createCommentEle.attr("href")));
		// str_id
		album.str_id = new File(filePath).getParentFile().getName();
		// name
		album.name = albumDoc.select("div#title > h1").first().ownText();
		// tags
		Elements tagEles = albumDoc.select("div#album_tags_block>div.content").select(".clearfix>a");
		for(Element tagEle : tagEles) {
			String tagIDstr = tagEle.attr("id");
			if (tagIDstr.lastIndexOf('_')==tagIDstr.length()-1)
				continue;
			long tagID = Long.parseLong(tagIDstr.substring(tagIDstr.lastIndexOf('_')+1));
			String tagContent = tagEle.text();
			if (tagContent.length()>20)
				continue;
			insertTag(tagID, tagContent);
			album.tags.add(tagID);
		}
		// genres
		Elements genreEles = albumDoc.select("div#album_info>table>tbody>tr a");
		for(Element genreEle : genreEles) {
			String href = genreEle.attr("href");
			if(href.contains("/detail/sid/")) {
				long genreID = Long.parseLong(extractID(href));
				album.genres.add(genreID);
				insertGenre(genreID, genreEle.text());
			}
		}
		// artist str_id
		Element artistEle = albumDoc.select("div#album_info>table>tbody>tr>td>a").first();
		artist.str_id = extractID(artistEle.attr("href"));
		// cnts
		Elements cntEles = albumDoc.select("div.music_counts>ul.clearfix>li");
		album.play_cnt = Long.parseLong(cntEles.get(0).ownText());
		album.collect_cnt = Long.parseLong(cntEles.get(1).ownText());
		album.comments_cnt = Long.parseLong(cntEles.get(2).select("a[href=#wall]>i").text());
		// rank
		Element infoEle = albumDoc.select("div#album_info").first();
		String scoreStr = infoEle.select("div#album_rank>p>em").first().text();
		album.score = 5.0; //defaults score
		if (scoreStr!=null && scoreStr.length()>0)
			album.score = Double.parseDouble(scoreStr);
		Elements rankEles = infoEle.select("ul>li");
		album.rank5 = Long.parseLong(rankEles.get(0).ownText());
		album.rank4 = Long.parseLong(rankEles.get(1).ownText());
		album.rank3 = Long.parseLong(rankEles.get(2).ownText());
		album.rank2 = Long.parseLong(rankEles.get(3).ownText());
		album.rank1 = Long.parseLong(rankEles.get(4).ownText());
		// song
		File albumFolder = new File(filePath).getParentFile();
		File[] songFiles = albumFolder.listFiles();
		for(File songFile : songFiles) {
			if (songFile.getName().contains(".song")) {
				Song song = parseSong(songFile.getPath(), artist, album);
				album.songs.add(song);
			}
		}
		return album;
	}
	
	public Song parseSong(String filePath, Artist artist, Album album) {
		Document songDoc = Jsoup.parse(fileContent(filePath));
		Song song = new Song();
		song.artist_id = artist.artist_id;
		song.album_id = album.album_id;
		// str_id
		song.str_id = new File(filePath).getName();
		song.str_id = song.str_id.substring(0, song.str_id.lastIndexOf('.'));
		// id
		Element idEle = songDoc.select("div#qrcode a.qrcode>img").first();
		song.song_id = Long.parseLong(extractID(idEle.attr("src")));
		//name
		song.name = songDoc.select("div#title > h1").first().ownText();
		// tags
		Elements tagEles = songDoc.select("div#song_tags_block>div.content").select(".clearfix>a");
		for(Element tagEle : tagEles) {
			String tagIDstr = tagEle.attr("id");
			if (tagIDstr.lastIndexOf('_')==tagIDstr.length()-1)
				continue;
			long tagID = Long.parseLong(tagIDstr.substring(tagIDstr.lastIndexOf('_')+1));
			String tagContent = tagEle.text();
			if (tagContent.length()>20)
				continue;
			insertTag(tagID, tagContent);
			song.tags.add(tagID);
		}
		// cnts
		Elements cntEles = songDoc.select("div.music_counts>ul.clearfix>li");
		//song.play_cnt = Long.parseLong(cntEles.get(0).select("em#play_count_num").text());
		song.share_cnt = Long.parseLong(cntEles.get(1).ownText());
		song.comments_cnt = Long.parseLong(cntEles.get(2).select("a[href=#wall]").first().ownText());
		return song;
	}
	
	private String fileContent(String file) {
		String ret = null;
		try (InputStream inPage = new FileInputStream(file)) {
			ret = IOUtils.toString(inPage, "utf-8");
		} catch (IOException e) {
			e.printStackTrace();
		}
		return ret;
	}
	
	private String extractID(String href) {
		int firstIdx = href.lastIndexOf('/'), lastIdx = href.lastIndexOf('?');
		lastIdx = (lastIdx<0)?href.length():lastIdx;
		return href.substring(firstIdx+1, lastIdx);
	}
	
	
	private boolean insertGenre(long genre_id, String genre) {
		try {
			PreparedStatement stmt = conn.prepareStatement("replace into genres values(?,?)");
			stmt.setLong(1, genre_id);
			stmt.setString(2, genre);
			stmt.executeUpdate();
			stmt.close();
			return true;
		} catch (SQLException e) {
			e.printStackTrace();
			return false;
		}
	}
	
	private boolean insertTag(long tag_id, String tag) {
		try {
			PreparedStatement stmt = conn.prepareStatement("replace into tags values(?,?)");
			stmt.setLong(1, tag_id);
			stmt.setString(2, tag);
			stmt.executeUpdate();
			stmt.close();
			return true;
		} catch (SQLException e) {
			e.printStackTrace();
			return false;
		}
	}
}
