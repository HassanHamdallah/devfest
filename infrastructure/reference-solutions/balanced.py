s=input().strip()
n=0
ok=True
for c in s:
    n += 1 if c == '(' else -1
    if n < 0: ok=False
print('YES' if ok and n == 0 else 'NO')
