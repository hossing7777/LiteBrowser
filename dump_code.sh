#!/bin/bash
# dump_code.sh
# این اسکریپت تمام سورس کدهای متنی پروژه را در یک فایل تجمیع می‌کند.

OUTPUT_FILE="Full_Source_Code.txt"

echo "Generating full source code dump..."

# خالی کردن فایل خروجی اگر از قبل وجود دارد
> "$OUTPUT_FILE"

# لیست پسوندهای فایل‌هایی که می‌خواهیم کپی کنیم
EXTENSIONS=("*.kt" "*.java" "*.xml" "*.kts" "*.gradle" "*.yml" "*.yaml" "*.properties" "*.sh" "*.py" "*.txt" "*.md" "*.c" "*.h")

# ساخت لیست فایل‌ها با استفاده از find و سپس مرتب کردن آن‌ها
FILES=$(find . -type f \( \
    -name "*.kt" -o \
    -name "*.java" -o \
    -name "*.xml" -o \
    -name "*.kts" -o \
    -name "*.gradle" -o \
    -name "*.yml" -o \
    -name "*.yaml" -o \
    -name "*.properties" -o \
    -name "*.sh" -o \
    -name "*.py" -o \
    -name "*.c" -o \
    -name "*.h" \
    \) -not -path "*/.*" -not -path "*/build/*" -not -path "*/.gradle/*" -not -path "*/app/build/*" -not -path "*/local.properties" | sort)

# اضافه کردن هدر
echo "======================================================================" >> "$OUTPUT_FILE"
echo "PROJECT: LiteBrowser Full Source Code Dump" >> "$OUTPUT_FILE"
echo "DATE: $(date)" >> "$OUTPUT_FILE"
echo "======================================================================" >> "$OUTPUT_FILE"
echo "" >> "$OUTPUT_FILE"

# خواندن هر فایل و نوشتن در فایل خروجی
for file in $FILES; do
    # نادیده گرفتن فایل خروجی خودمان
    if [[ "$file" == "./$OUTPUT_FILE" ]]; then
        continue
    fi
    
    echo "----------------------------------------------------------------------" >> "$OUTPUT_FILE"
    echo "FILE: $file" >> "$OUTPUT_FILE"
    echo "----------------------------------------------------------------------" >> "$OUTPUT_FILE"
    cat "$file" >> "$OUTPUT_FILE"
    echo -e "\n\n" >> "$OUTPUT_FILE"
done

echo "Done! All code saved to $OUTPUT_FILE"
echo "Total lines: $(wc -l < "$OUTPUT_FILE")"
