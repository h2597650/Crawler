import math, os
if not os.path.exists('split'):
    os.mkdir('split')
vec = []
with open('songs.txt', 'r') as f:
    for line in f:
        vec.append(line)
s_size = 50000
for i in range(int(math.ceil(len(vec)/float(s_size)))):
    file_name = 'split/%d-%d.txt' % (i*s_size, (i+1)*s_size)
    with open(file_name, 'w') as f:
        for idx in range(i*s_size, min(len(vec),(i+1)*s_size)):
            f.write('http://www.xiami.com/song/' + vec[idx])

