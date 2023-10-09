##### Java proxy server without Spring and Tomcat

How it works:

1. Firstly, you specify which addresses you want to proxy in `hosts` file. 
2. Target IP, specified in hosts file must lead to machine with running nginx
3. Nginx forwards requests to actual proxy server. Request url is specified in headers, e.g.
   - `X-Protocol` = `http`
   - `Host` = `google.com`
   - `X-Original-URI` = `/images`  
   Complete example you can see in `nginx.conf` file
4. Proxy server handles all http methods, sends request to original server and returns byte data result
