classpath="lib/htmlcleaner-2.8.jar:lib/json.jar:lib/jsoup-1.10.2.jar:lib/commons-lang-2.6.jar"
javac -cp .:$classpath com/*.java
java -cp .:$classpath com.LongCrawler
