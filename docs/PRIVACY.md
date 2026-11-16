# 隐私说明

大米保质期把物品、家庭和照片存在你登录的家庭里。手机号只以加密形式交给服务器。

## 相机、条码和文字识别

扫码和拍包装使用设备上的相机。照片用于识别日期，并在你确认后上传到你的家庭清单。识别在手机上完成，不把包装照片发给 Google 做识别。

用到的库是 Google ML Kit 的打包模型：

- 中文文字识别：<https://developers.google.com/ml-kit/vision/text-recognition/v2/android>
- 条码扫描：<https://developers.google.com/ml-kit/vision/barcode-scanning/android>

这些是随应用打包的模型，不从 Google Play 服务下载模型文件。设备上如果装有 Google Play 服务，ML Kit 仍可能通过 Play services 的使用情况统计上报「功能被调用」这一类事件。官方文档没有提供关闭这项统计的开关。应用不会另外打开 ML Kit 的日志，也不会把识别出的文字或条码内容发给 Google。

条码名称查询由我们的服务器代为请求 Open Food Facts，只发送数字条码，并有按用户的频率限制。
