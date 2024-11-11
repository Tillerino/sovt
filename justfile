mvn := `if command -v mvnd &> /dev/null; then echo mvnd; else echo mvn; fi`

updates-flags := "-q '-Dmaven.version.ignore=.*\\.Beta\\d*,.*\\.BETA\\d*,.*-beta-\\d*,.*\\.android\\d*,.*-M\\d' -Dversions.outputFile=updates.txt -Dversions.outputLineWidth=1000 -P release"

# lists all recipes
@recipes:
  just --list

# apply spotless format
format:
  {{mvn}} spotless:apply -Dmaven.build.cache.skipCache=true

# show all available updates
updates:
  {{mvn}} versions:display-plugin-updates {{updates-flags}} && { grep -- "->" updates.txt | sed 's/\.\+/./g'; }
  {{mvn}} versions:display-property-updates {{updates-flags}} && { grep -- "->" updates.txt | sed 's/\.\+/./g'; }
  {{mvn}} versions:display-dependency-updates {{updates-flags}} && { grep -- "->" updates.txt | sed 's/\.\+/./g'; }
  rm updates.txt

release:
    # We'll just warm up gpg here
    echo hi | gpg -s --pinentry-mode loopback
    # format so that we fail earlier if there are issues (release plugin will notice dirty working directory)
    just format
    # don't use mvnd here. no need to overoptimize
    mvn release:prepare -DtagNameFormat=@{project.version} '-Darguments=-Dmaven.build.cache.skipCache=true'
    mvn release:perform -P release '-Darguments=-Dmaven.build.cache.skipCache=true'
