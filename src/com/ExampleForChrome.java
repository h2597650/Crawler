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
        // 设置 chrome 的路径  
    	System.setProperty("webdriver.chrome.driver", "C:/Program Files (x86)/Google/Chrome/Application/chromedriver.exe");
    	WebDriver driver = new ChromeDriver(); 
  
        // 让浏览器访问 Baidu  
        driver.get("http://music.baidu.com/artist/1115");  
        // 用下面代码也可以实现  
        // driver.navigate().to("http://www.baidu.com");  
  
        // 获取 网页的 title  
        System.out.println("1 Page title is: " + driver.getTitle());  
        
        //driver.findElement(By.id("kw")).sendKeys("test");
        driver.findElement(By.id("page-navigator-next")).click();
        //driver.quit();
  
        /*
        // 通过 id 找到 input 的 DOM  
        WebElement element = driver.findElement(By.id("kw"));  
  
        // 输入关键字  
        element.sendKeys("zTree");  
  
        // 提交 input 所在的 form  
        element.submit();  
  
        // 关闭浏览器  
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