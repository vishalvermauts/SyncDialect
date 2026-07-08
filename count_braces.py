import sys

depth = 0
found_class = False

with open('app/src/main/java/com/example/auravoice/MainActivity.kt', 'r', encoding='utf-8') as f:
    for i, line in enumerate(f):
        if "class MainActivity" in line:
            found_class = True
            
        if not found_class:
            continue
            
        clean_line = line.split('//')[0]
        depth += clean_line.count('{')
        depth -= clean_line.count('}')
        
        if (730 <= i <= 760):
            print(f"Line {i+1} | Depth: {depth} | {line.strip()}")
