package com;

import java.io.IOException;
import java.util.ArrayList;

import org.htmlcleaner.HtmlCleaner;
import org.htmlcleaner.TagNode;
import org.htmlcleaner.XPatherException;
import org.json.JSONObject;
import org.apache.commons.lang.StringUtils;

public class SongInfo {
	private String artist;
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
			return null;
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
	
	public boolean setValues(String pageContents)
	{
		TagNode tag = null;
		tag = new HtmlCleaner().clean(pageContents);
		try {
			TagNode baseInfo = (TagNode) tag.evaluateXPath("/body//ul[@class = \"base-info c6\"]")[0];
			
			artist = getTagText(baseInfo, "/li/span[@class=\"author_list\"]/a");
			name = getAttribute(baseInfo, "/li[@class=\"clearfix\"]/a", "title");
			try {
				album = getTagText(baseInfo, "/li[@class=\"clearfix\"]/a");
			} catch (XPatherException e) {
				album = "";
			}
			tags = getMultiTagText(baseInfo, "/li[@class=\"clearfix tag\"]/a[@class=\"tag-list\"]");
			if(tags==null)
				tags = new ArrayList<String>();
			//String str = StringUtils.join(list.toArray(), ",");
			return true;
		} catch (Exception e) {
			System.out.println(pageContents);
			e.printStackTrace();
		}
		return false;
	}
	
	public String toLineXML()
	{
		StringBuilder line = new StringBuilder();
		line.append("\t<song>\n");
		line.append("\t\t<name>" + this.name + "</name>\n");
		line.append("\t\t<artist>" + this.artist + "</artist>\n");
		line.append("\t\t<album>" + this.album + "</album>\n");
		line.append("\t\t<tags>" + StringUtils.join(this.tags.toArray(), ",") + "</tags>\n");
		line.append("\t</song>");
		return line.toString();
	}
	
	public String toLineJSON()
	{
		JSONObject json=new JSONObject();
		json.put("name", this.name);
		json.put("artist", this.artist);
		json.put("album", this.album);
		json.put("tags", StringUtils.join(this.tags.toArray(), ","));
		return json.toString();
	}
}
