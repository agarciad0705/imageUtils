import sys
from PIL import Image
 
img = Image.open(sys.argv[1])
img.save(sys.argv[2])
img = img.convert("RGBA")
datas = img.getdata()
 
newData = []
for item in datas:
    if item[0] == 255 and item[1] == 255 and item[2] == 255:
        newData.append((255, 255, 255, 0))
    else:
        newData.append(item)
 
img.putdata(newData)
img.save(sys.argv[2], "PNG")

# JAVA IMPLEMENT
# String fileUriThumbColor = fileUriThumb.replace("imageFingerprintThumb", "imageFingerprintThumbColor");       
# StringBuilder commandColor = new StringBuilder("python ").append(scriptColorImage).append(" ")
#                .append(fileUriThumb).append(" ")
#                .append(fileUriThumbColor);
#        log.info("Executing: {}", commandColor.toString());
#        try {
#            CommandLine cmdLine = CommandLine.parse(commandColor.toString());
#            DefaultExecutor defaultExecutor = new DefaultExecutor();
#            defaultExecutor.execute(cmdLine);
#            Thread.sleep(500);
#        } catch (Exception e) {
#            log.error("Error coloring image with message: {}", e.getMessage());
#            log.error("Trace: ", e);
#            fileUriThumbColor = fileUriThumb;
#        }
