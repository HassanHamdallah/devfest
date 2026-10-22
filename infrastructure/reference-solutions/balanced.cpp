#include <iostream>
#include <string>
int main(){std::string s;std::cin>>s;int n=0;bool ok=true;for(char c:s){n+=c=='('?1:-1;if(n<0)ok=false;}std::cout<<(ok&&n==0?"YES":"NO")<<"\n";}
