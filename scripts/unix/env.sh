#!/bin/sh
# ----------------------------------------------------------------------------
#
# This variable should be set to the directory where is installed ProActive
#

workingDir=`dirname $0`
PROACTIVE=$workingDir/../../.

# ----------------------------------------------------------------------------

JAVA_HOME=${JAVA_HOME-NULL};
if [ "$JAVA_HOME" = "NULL" ]
then
echo
echo "The enviroment variable JAVA_HOME must be set the current jdk distribution"
echo "installed on your computer."
echo "Use "
echo "    export JAVA_HOME=<the directory where is the JDK>"
exit 127
fi

# ----
# Set up the classpath using classes dir or jar files
#
CLASSPATH=.
if [ -d $PROACTIVE/classes ]
then
    CLASSPATH=$CLASSPATH:$PROACTIVE/classes
fi
if [ -f $PROACTIVE/ProActive.jar ]
then
    CLASSPATH=$CLASSPATH:$PROACTIVE/ProActive.jar
fi
if [ -f $PROACTIVE/lib/bcel.jar ]
then
    CLASSPATH=$CLASSPATH:$PROACTIVE/lib/bcel.jar
fi
if [ -f $PROACTIVE/lib/asm.jar ]
then
    CLASSPATH=$CLASSPATH:$PROACTIVE/lib/asm.jar
fi
if [ -f $PROACTIVE/ProActive_examples.jar ]
then
    CLASSPATH=$CLASSPATH:$PROACTIVE/ProActive_examples.jar
fi
if [ -f $PROACTIVE/ic2d.jar ]
then
    CLASSPATH=$CLASSPATH:$PROACTIVE/ic2d.jar
fi
if [ -f $PROACTIVE/lib/jini-core.jar ]
then
    CLASSPATH=$CLASSPATH:$PROACTIVE/lib/jini-core.jar
fi
if [ -f $PROACTIVE/lib/jini-ext.jar ]
then
    CLASSPATH=$CLASSPATH:$PROACTIVE/lib/jini-ext.jar
fi
if [ -f $PROACTIVE/lib/reggie.jar ]
then
    CLASSPATH=$CLASSPATH:$PROACTIVE/lib/reggie.jar
fi


CLASSPATH=$CLASSPATH:$PROACTIVE/lib/asm.jar

echo "CLASSPATH"=$CLASSPATH
export CLASSPATH

JAVACMD=$JAVA_HOME"/bin/java -Djava.security.manager -Djava.security.policy=$workingDir/proactive.java.policy"
export JAVACMD
