package com;

import java.io.IOException;  

import org.openqa.selenium.By;  
import org.openqa.selenium.WebDriver;  
import org.openqa.selenium.WebElement;  
import org.openqa.selenium.chrome.ChromeDriver;  
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.ie.InternetExplorerDriver;
import org.openqa.selenium.support.ui.ExpectedCondition;  
import org.openqa.selenium.support.ui.WebDriverWait;  
  
public class ExampleForChrome {  
    public static void main(String[] args) throws IOException {  
        // ���� chrome ��·��  
    	System.setProperty("webdriver.chrome.driver", "C:/Program Files (x86)/Google/Chrome/Application/chromedriver.exe");
    	WebDriver driver = new ChromeDriver(); 
  
        // ����������� Baidu  
        driver.get("http://music.baidu.com/artist/1115");  
        // ���������Ҳ����ʵ��  
        // driver.navigate().to("http://www.baidu.com");  
  
        // ��ȡ ��ҳ�� title  
        System.out.println("1 Page title is: " + driver.getTitle());  
        
        //driver.findElement(By.id("kw")).sendKeys("test");
        driver.findElement(By.id("page-navigator-next")).click();
        //driver.quit();
  
        /*
        // ͨ�� id �ҵ� input �� DOM  
        WebElement element = driver.findElement(By.id("kw"));  
  
        // ����ؼ���  
        element.sendKeys("zTree");  
  
        // �ύ input ���ڵ� form  
        element.submit();  
  
        // �ر������  
        driver.quit();  
        */
  
        // element = driver.findElement(By.id("kw"));  
        // // element.clear();  
        // element.click();  
        // element.clear();  
        // element.sendKeys("zTree");  
        // element.submit();  
    }  
}  