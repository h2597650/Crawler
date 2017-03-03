package com;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

import org.apache.log4j.Logger;
import org.dom4j.io.OutputFormat;

public class MyFileWriter {
	private Logger logger = Logger.getLogger(MyFileWriter.class);
	private String path;
	
	public MyFileWriter(String _path){
		path = _path;
	}
	
	public boolean write2file(String filename,String html,String charset){
		
		boolean ans = true;
		if (! FileDirUtil.PathIsExist(path)) {
			
			if ( ! FileDirUtil.CreateDirectory(path)) {
				logger.error("创建目录失败");
				ans = false;
			}
		}
		
		FileOutputStream fOStream = null;
		try {
			fOStream = new FileOutputStream(new File(path + "/" + filename ));
			fOStream.write(html.getBytes(charset));
		} catch (FileNotFoundException e) {
			logger.error(e.getMessage(),e);
			ans = false;
		} catch (IOException e) {
			logger.error(e.getMessage(),e);
			ans = false;
		} finally{
			if( fOStream != null )
				try {
					fOStream.close();
				} catch (IOException e) {
					logger.error(e.getMessage(),e);
					ans = false;
				}
		}
		
		return ans;
	}
}
