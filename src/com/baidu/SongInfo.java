package com.baidu;

import java.io.IOException;
import java.util.ArrayList;

import org.htmlcleaner.HtmlCleaner;
import org.htmlcleaner.TagNode;
import org.htmlcleaner.XPatherException;
import org.json.JSONObject;
import org.apache.commons.lang.StringUtils;

public class SongInfo {
	private ArrayList<String> artist;
	private String name;
	private String album;
	private ArrayList<String> tags;
	
	private String getAttribute(TagNode tag, String xpath, String attr) throws XPatherException{
		Object[] nodes = null;
		nodes = tag.evaluateXPath(xpath);
		if( nodes == null || nodes.length == 0 ){
			return "";
		}
		else{
			TagNode meta = (TagNode) nodes[0];
			return meta.getAttributeByName(attr).trim();
		}
	}
	
	private String getTagText(TagNode tag, String xpath) throws XPatherException{
		Object[] nodes = null;
		nodes = tag.evaluateXPath(xpath);
		if( nodes == null || nodes.length == 0 ){
			return "";
		}
		else{
			TagNode t = (TagNode) nodes[0];
			return t.getText().toString().trim();
		}
	}
	
	private ArrayList<String> getMultiTagText(TagNode tag, String xpath) throws XPatherException{
		Object[] nodes = null;
		nodes = tag.evaluateXPath(xpath);
		if( nodes == null || nodes.length == 0 ){
			return new ArrayList<String>();
		}
		else{
			ArrayList<String> ret = new ArrayList<String>();
			for(Object tnode : nodes)
			{
				ret.add(((TagNode)tnode).getText().toString().trim());
			}
			return ret;
		}
	}
	
	public boolean setValues(String pageurl, String pageContents)
	{
		TagNode tag = null;
		tag = new HtmlCleaner().clean(pageContents);
		try {
			TagNode baseInfo = (TagNode) tag.evaluateXPath("/body//ul[@class = \"base-info c6\"]")[0];
			
			artist = getMultiTagText(baseInfo, "/li/span[@class=\"author_list\"]/a");
			name = getAttribute(baseInfo, "/li[@class=\"clearfix\"]/a", "title");
			try {
				album = getTagText(baseInfo, "/li[@class=\"clearfix\"]/a");
			} catch (XPatherException e) {
				album = "";
			}
			tags = getMultiTagText(baseInfo, "/li[@class=\"clearfix tag\"]/a[@class=\"tag-list\"]");
			return true;
		} catch (Exception e) {
			System.err.println("Parse error : " + pageurl);
			//e.printStackTrace();
		}
		return false;
	}
	
	public String toLineXML()
	{
		StringBuilder line = new StringBuilder();
		line.append("\t<song>\n");
		line.append("\t\t<name>" + this.name + "</name>\n");
		line.append("\t\t<artist>" + StringUtils.join(this.artist.toArray(), ",") + "</artist>\n");
		line.append("\t\t<album>" + this.album + "</album>\n");
		line.append("\t\t<tags>" + StringUtils.join(this.tags.toArray(), ",") + "</tags>\n");
		line.append("\t</song>");
		return line.toString();
	}
	
	public String toLineJSON()
	{
		JSONObject json=new JSONObject();
		json.put("name", this.name);
		json.put("artist", StringUtils.join(this.artist.toArray(), ","));
		json.put("album", this.album);
		json.put("tags", StringUtils.join(this.tags.toArray(), ","));
		return json.toString();
	}
}
