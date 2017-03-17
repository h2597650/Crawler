for ijar in lib/*.jar; do
    classpath="$ijar:$classpath"
done;
find src -name *.java > src/sources.list

mkdir -p bin
javac -cp .:$classpath -s src -d bin @src/sources.list
nohup java -cp .:bin:$classpath com.xiami.XiamiMysql > nohup.xiamimysql&
sleep 3
tail -f nohup.xiamimysql
