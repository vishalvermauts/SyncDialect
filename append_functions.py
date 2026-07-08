import sys

def main():
    lines = open('temp_settings.txt', encoding='utf-8').readlines()
    cleaned_lines = []
    for line in lines:
        if ":" in line:
            parts = line.split(":", 1)
            if parts[0].strip().isdigit():
                cleaned_lines.append(parts[1][1:]) # strip the space after colon
            else:
                cleaned_lines.append(line)
        else:
            cleaned_lines.append(line)

    content = "".join(cleaned_lines)
    
    start_str = "fun getFlagEmoji"
    idx = content.find(start_str)
    if idx != -1:
        functions_str = content[idx:]
        
        with open('app/src/main/java/com/syncdialect/app/SettingsScreen.kt', 'a', encoding='utf-8') as f:
            f.write("\n\n" + functions_str)
        print("Appended successfully.")
    else:
        print("Could not find fun getFlagEmoji")

if __name__ == "__main__":
    main()
