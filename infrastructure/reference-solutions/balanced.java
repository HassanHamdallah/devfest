import java.util.Scanner;
class Main {public static void main(String[] args){String s=new Scanner(System.in).next();int n=0;boolean ok=true;for(char c:s.toCharArray()){n+=c=='('?1:-1;if(n<0)ok=false;}System.out.println(ok&&n==0?"YES":"NO");}}
