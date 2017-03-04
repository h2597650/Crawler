classpath="lib/htmlcleaner-2.8.jar:lib/json.jar:lib/jsoup-1.10.2.jar:lib/commons-lang-2.6.jar"
find src -name *.java > src/sources.list

mkdir -p bin
javac -cp .:$classpath -s src -d bin @src/sources.list
java -cp .:bin:$classpath com.baidu.LongCrawler
