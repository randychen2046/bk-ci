/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2019 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"go/format"
	"io/ioutil"
	"os"
	"path/filepath"
	"strings"

	"golang.org/x/text/language"
)

func main() {
	fmt.Fprintf(os.Stdout, "start running translation_generator...\n")
	workDir, err := os.Getwd()
	if err != nil {
		fmt.Fprintf(os.Stderr, "get workdir error %s \n", err.Error())
		return
	}

	// 拿到项目的根目录，目前的运行目录是 (root)/src/pkg/i18n/, 所以需要往上三个目录
	rootPath := filepath.Dir(filepath.Dir(filepath.Dir(workDir)))

	g := Generator{}

	// 打印文件头和包引用
	g.Printf("// Code generated by \"translation_generator\"; DO NOT EDIT.\n")
	g.Printf("\n")
	g.Printf("package %s", "translation")
	g.Printf("\n")
	g.Printf("import \"github.com/nicksnyder/go-i18n/v2/i18n\"\n")
	g.Printf("\n")
	g.Printf("// Translations 保存当前所有的翻译\n")
	g.Printf("var Translations map[string][]*i18n.Message = make(map[string][]*i18n.Message)\n")
	g.Printf("func init(){\n")

	// 读取用户配置的国际化文件
	i18nFileDir := filepath.Join(rootPath, "i18n")
	files, err := ioutil.ReadDir(i18nFileDir)
	if err != nil {
		fmt.Fprintf(os.Stderr, "read i18ndir error %s \n", err.Error())
		return
	}
	for _, f := range files {
		if f.IsDir() {
			continue
		}
		// 文件名称就作为需要国际化的语言的key
		fileName := f.Name()
		fmt.Fprintf(os.Stdout, "start read language file %s ...\n", fileName)

		fileContent, err := os.ReadFile(filepath.Join(i18nFileDir, fileName))
		if err != nil {
			fmt.Fprintf(os.Stderr, "read i18nfile %s error %s \n", filepath.Join(i18nFileDir, fileName), err.Error())
			return
		}

		// 解析国际化内容
		lanuageValue := &map[string]map[string]string{}
		err = json.Unmarshal(fileContent, lanuageValue)
		if err != nil {
			fmt.Fprintf(os.Stderr, "json umarshal i18nfile %s error %s \n", filepath.Join(i18nFileDir, fileName), err.Error())
			return
		}

		// 打印代码内容
		// 获取并校验语言类型
		lanuagStr := strings.TrimSuffix(fileName, ".json")
		lanuagTag, err := language.Parse(lanuagStr)
		if err != nil {
			fmt.Fprintf(os.Stderr, "go not support lanuage name %s error %s\n", lanuagStr, err.Error())
			return
		}
		g.Printf("Translations[\"%s\"] = []*i18n.Message{\n", lanuagTag.String())
		// 拼接 i18n.Message 对象，并校验
		for id, v := range *lanuageValue {
			g.Printf("{\n")
			if id == "" {
				fmt.Fprintf(os.Stderr, "build i18nmessage error file %s id is blank \n", filepath.Join(i18nFileDir, fileName))
				return
			}
			g.Printf("ID: \"%s\",", id)
			other, ok := v["other"]
			if !ok {
				fmt.Fprintf(os.Stderr, "build i18nmessage error file %s id %s no 'other' key \n", filepath.Join(i18nFileDir, fileName), id)
				return
			}
			// 将换行符转义
			other = strings.ReplaceAll(other, "\n", "\\n")
			g.Printf("Other: \"%s\",", other)
			g.Printf("},\n")
		}

		g.Printf("}\n")
		fmt.Fprintf(os.Stdout, "language file %s build done\n", fileName)
	}
	g.Printf("}\n")

	// 格式化输出
	src := g.format()
	// 写入到 translation.go 文件中
	outputName := filepath.Join(workDir, "translation", "translation.go")
	err = os.WriteFile(outputName, src, 0644)
	if err != nil {
		fmt.Fprintf(os.Stderr, "writing output: %s", err)
		return
	}
}

// 生成器保存分析的状态。 主要用来缓冲 format.Source 的输出。
type Generator struct {
	buf bytes.Buffer // 累计输出
}

func (g *Generator) Printf(format string, args ...interface{}) {
	fmt.Fprintf(&g.buf, format, args...)
}

// 格式返回生成器缓冲区的 gofmt-ed 内容。
func (g *Generator) format() []byte {
	src, err := format.Source(g.buf.Bytes())
	if err != nil {
		return g.buf.Bytes()
	}
	return src
}
