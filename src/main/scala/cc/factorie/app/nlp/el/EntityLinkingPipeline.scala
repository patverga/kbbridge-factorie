package cc.factorie.app.nlp.el

import cc.factorie.app.nlp._
import cc.factorie.app.nlp.segment.ClearSegmenter
import cc.factorie.app.nlp.pos.POS1
import org.lemurproject.galago.core.retrieval.{RetrievalFactory, Retrieval}
import java.io.{StringReader, File}
import org.lemurproject.galago.tupleflow.Parameters
import scala.xml.parsing.ConstructingParser
import scala.io.Source
import scala.xml.{NodeSeq, XML}
import org.xml.sax.InputSource
import cc.factorie.CategoricalVar
import com.typesafe.scalalogging.slf4j.Logging

//import com.googlecode.clearnlp.morphology.EnglishMPAnalyzer

import cc.factorie.app.nlp.ner.NER1
import cc.factorie.app.nlp.parse.DepParser2
import cc.factorie.app.nlp.mention.{MentionType, ParseBasedMentionFinding}

object LinkingAnnotatorMain extends App with Logging {

  val nlpSteps = Seq(

    ClearSegmenter,
    // Truecasing??
    POS1,
    // LemmaAnnotator,
    NER1,
    //FactorieNERComponent,
    DepParser2,
    ParseBasedMentionFinding,
    KbBridgeEntityLinking
  )

  val p = new Parameters()

  var docs: Seq[String] = args(0).split(",").toSeq
  val outputDir: File = new File(args(2))
  p.set("index", args(1))
  //outputDir = new File(args(2))
  if (!outputDir.exists()) {
    outputDir.mkdirs()
  }

  println("docs to annotate: " + docs.size)


  if (workTodo(docs)) {
    p.set("terms", true)
    p.set("tags", true)
    val retrieval = RetrievalFactory.instance(p)

    annotateDocs(docs, retrieval)
  }

  def annotateDocs(docs: Seq[String], retrieval: Retrieval) = {
    var numAnnotated = 0
    for (docId <- docs) {
      val outputFile = new File(outputDir.getAbsolutePath + File.separator + docId + ".xml")

      if (!outputFile.exists()) {
        val gDoc = retrieval.getDocument(docId, p)

        if (gDoc != null) {
          println("Annotating document: " + docId + " numAnnotated:" + numAnnotated)


          //val docXml = XML.loadString(gDoc.text)
          // val newsDoc = Text2FactorieDoc.newswire(b)

          val parser = new org.ccil.cowan.tagsoup.jaxp.SAXFactoryImpl().newSAXParser()
          val adapter = new scala.xml.parsing.NoBindingFactoryAdapter
          val xmlDoc = adapter.loadXML(new InputSource(new StringReader(gDoc.text)), parser)
          // println(xmlDoc.toString())
          val text = xmlDoc \\ "TEXT"
          // println(text.text)


          val headline = xmlDoc \\ "HEADLINE"
          val doc = Text2FactorieDoc.news(headline, text)

          // val doc = new Document(textDoc)

          // println(doc.tokens.mkString())
          import Implicits.defaultDocumentAnnotatorMap

          doc.setName(gDoc.name)
          for (step <- nlpSteps) {
            step.process(doc)
          }
          println("Processed %d tokens.".format(doc.tokenCount))
          println(doc.owplString(nlpSteps.map(p => p.tokenAnnotationString(_))))

          val xml = Document2XmlRenderer.xml(doc)
          //println(xml.toString)

          XML.save(outputFile.getAbsolutePath, xml, "UTF-8")
        }
      }


      //        val xml = new toXml(doc, links)
      //        println(xml.makeDocument.toString)
      //        xml.save(outputFile)
    }
  }


  def workTodo(docs: Seq[String]): Boolean = {
    var workTodo = false
    for (docId <- docs) {
      val outputFile = new File(outputDir.getAbsolutePath + File.separator + docId + ".xml")
      if (!outputFile.exists()) {
        workTodo = true
      }
    }
    workTodo
  }

}

object Text2FactorieDoc {

  def news(headline: NodeSeq, text: NodeSeq): Document = {
    val paragraphs = (text \\ "p")
    val headlineText = headline.text.replace("\n", " ")

    val mText = if (paragraphs.size > 0) {
      paragraphs.map(node => node.text.trim.replace("\n", " ")).mkString("\n\n")
    } else {
      text.text.replace("\n", " ")
    }
    val cleanLayout = removeLayout(headlineText ++ ". \n\n" + mText)
   // println("TEXT:\n" + cleanLayout)
    new Document(cleanLayout)
  }

  def ensurePunc(string: String): String = {
    if (string.trim.split("\n").last.matches(".*[\\.?!]$")) string.trim else string.trim + "."
  }

  def removeLayout(string: String): String = {
    string.replaceAll("====*|----*|\\*\\*\\*\\**|XXXX*", ".").replaceAll("(\\.( \n|\n |\n| )?)(\\.( \n|\n |\n| )?)+", ".")
  }

  def unescape(string: String): String = {
    val s = "<doc>" + string.replaceAll("&(?![a-z]{2,4};)", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;") + "</doc>"
    val d = ConstructingParser.fromSource(Source.fromString(s), preserveWS = true).document()
    d(0).text
  }

}





object Document2XmlRenderer {

  def sgmlString(doc: Document): String = {
    val buf = new StringBuffer
    for (section <- doc.sections; token <- section.tokens) {
      if (token.isSentenceStart) buf.append("<sentence>")
      token.startsSpans.foreach(span => buf.append("<" + span.name + ">"))
      buf.append(token.string)
      token.endsSpans.foreach(span => buf.append("</" + span.name + ">"))
      if (token.isSentenceEnd) buf.append("</sentence>")
      buf.append(" ")
    }
    buf.toString
  }

  def xml(doc: Document) = {
    <root>
      <document>
        <name>
          {doc.name}
        </name>
        <tokens>
          {for (section <- doc.sections; token <- section.tokens) yield
          <token id={(token.position + 1).toString}>
            <word>
              {token.string}
            </word>
            <lemma>
              {token.string}
            </lemma>
            <POS>
              {getAttr(token, POS1.tokenAnnotationString(_))}
            </POS>
            <CharacterOffsetBegin>
              {token.stringStart}
            </CharacterOffsetBegin>
            <CharacterOffsetEnd>
              {token.stringEnd}
            </CharacterOffsetEnd>
            <NER>
              {getAttr(token, NER1.tokenAnnotationString(_))}
            </NER>
            <PARSE>
              {getAttr(token, DepParser2.tokenAnnotationString(_))}
            </PARSE>
            <StartSentence>
              {token.isSentenceStart}
            </StartSentence>
          </token>}
        </tokens>
        <mentions>
          {for (m <- doc.attr[cc.factorie.app.nlp.mention.MentionList]) yield
          <mention>
            <string>
              {m.span.string}
            </string>
            <type>
              {m.attr[MentionType].categoryValue}
            </type>
            <CharacterOffsetBegin>
              {m.span.tokens.head.stringStart}
            </CharacterOffsetBegin>
            <CharacterOffsetEnd>
              {m.span.tokens.last.stringEnd}
            </CharacterOffsetEnd>
            <TokenBegin>
              {m.span.tokens.start}
            </TokenBegin>
            <TokenEnd>
              {m.span.tokens.start + m.span.tokens.length}
            </TokenEnd>
          </mention>}
        </mentions>
        <kblinks>
          {for (linkedMention <- doc.attr[WikiEntityMentions]) yield
          <entitylink>
            <name>
              {linkedMention.mention.span.string}
            </name>
            <CharacterOffsetBegin>
              {linkedMention.mention.span.tokens.head.stringStart}
            </CharacterOffsetBegin>
            <CharacterOffsetEnd>
              {linkedMention.mention.span.tokens.last.stringEnd}
            </CharacterOffsetEnd>
            <TokenBegin>
              {linkedMention.mention.span.tokens.start}
            </TokenBegin>
            <TokenEnd>
              {linkedMention.mention.span.tokens.start + linkedMention.mention.span.tokens.length}
            </TokenEnd>{for (c <- linkedMention.entityLinks) yield
            <candidate>
              <id>
                {c.wikipediaTitle}
              </id>
              <rank>
                {c.rank}
              </rank>
              <score>
                {c.score}
              </score>
            </candidate>}
          </entitylink>}
        </kblinks>
      </document>
    </root>
  }

  def getAttr(token: Token, af: (Token) => Any): String = {
    af(token) match {
      case cv: CategoricalVar[_, String@unchecked] => cv.categoryValue.toString
      case null => ""
      case v: Any => v.toString
    }
  }


}

