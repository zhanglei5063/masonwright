
public class Quine {
    public static void main(final String[] args) {
        final String[] str = { 
            "public class Quine {",
            "    public static void main(final String[] args) {",
            "        final String[] str = {",
            "    };",
            "        for (int i = 0; i < 3; i++) {System.out.println(str[i]);}",
            "        for (int i = 0; i < 9; i++) {System.out.println(str[9] + (char) 34 + str[i] + (char) 34 + ',');}",
            "        for (int i = 3; i < 9; i++) {System.out.println(str[i]);}",
            "    }",
            "}",
            "            "
        };
	
	for (int i = 0; i < 3; i++) {System.out.println(str[i]);}
	for (int i = 0; i < 9; i++) {System.out.println(str[9] + (char) 34 + str[i] + (char) 34 + ',');}
	for (int i = 3; i < 9; i++) {System.out.println(str[i]);}
    }
}

