package com.xiami;

import java.sql.*;

public class XiamiMysql {

	private Connection conn = null;
	
	public XiamiMysql(String user, String password, String folder) {
		connectJDBC(user, password);
	}
	
	private boolean connectJDBC(String user, String password){
		String DBDRIVER = "com.mysql.jdbc.Driver";    
	    try {
			Class.forName(DBDRIVER);
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
		}
	    
	    String url="jdbc:mysql://localhost:3306/xiami";   
        Connection conn;
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
	
	public static void main(String[] args) {
		String url="jdbc:mysql://localhost:3306/xiami";   
        Connection conn;
        try {
            conn = DriverManager.getConnection(url,"root","root");
            Statement stmt = conn.createStatement();
            stmt.close();
            conn.close();
            System.out.println("hehe");
        } catch (SQLException e){
            e.printStackTrace();
        }

	}

}
