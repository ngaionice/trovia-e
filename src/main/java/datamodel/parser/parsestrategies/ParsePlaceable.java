package datamodel.parser.parsestrategies;

import datamodel.objects.Article;
import datamodel.objects.ObservablePlaceable;
import local.Markers;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ParsePlaceable implements ParseStrategy {

    @Override
    public Article parseObject(String splitString, String absPath) throws ParseException {
        Markers m = new Markers();

        // check for $prefabs, if does not exist, likely not obtainable
        if (!splitString.contains(m.prefab)) {
            throw new ParseException("No name found, entity likely not obtainable.");
        }

        Pattern p = Pattern.compile(m.prefab);
        Matcher mt = p.matcher(splitString);
        int i = 0;
        int[] startIndices = new int[]{-1, -1};

        // identifies the first 2 $prefabs, corresponding to the start of the identifier for name and desc.
        while (mt.find()) {
            if (i > 2) break;
            startIndices[i] = mt.start();
            i++;
        }

        // identify 28 0\d, which marks the end of the name
        p = Pattern.compile("28 0\\d");
        mt = p.matcher(splitString);
        int nameEndIndex;
        if (mt.find()) {
            System.out.println(splitString.substring(mt.start(), mt.start() + 15));
            nameEndIndex = mt.start();
        } else {
            throw new ParseException("No end-of-name marker found.");
        }
        if (startIndices[0] == -1)
            throw new ParseException("No name found.");
        String name = splitString.substring(startIndices[0], nameEndIndex);

        // identify overall end marker
        p = Pattern.compile(m.endNameDesc);
        mt = p.matcher(splitString);
        int overallEndIndex;
        if (mt.find()) {
            overallEndIndex = mt.start();
        } else {
            throw new ParseException("No end-of-name/desc marker found.");
        }
        String desc = null;
        if (startIndices[1] != -1) {
            desc = splitString.substring(startIndices[1], overallEndIndex);
        }

        String rPath = absPath.substring(absPath.indexOf("prefabs\\")+8, absPath.indexOf(m.endFile));
        rPath = rPath.replaceAll("\\\\", "/");

        return new ObservablePlaceable(name, desc, rPath);
    }
}