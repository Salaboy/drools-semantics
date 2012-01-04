/*
 * Copyright 2011 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.drools.semantics.builder.model.inference;

import com.clarkparsia.pellet.owlapiv3.PelletReasonerFactory;
import org.apache.commons.collections15.map.MultiKeyMap;
import org.drools.io.Resource;
import org.drools.runtime.ClassObjectFilter;
import org.drools.runtime.StatefulKnowledgeSession;
import org.drools.semantics.builder.DLFactory;
import org.drools.semantics.builder.DLUtils;
import org.drools.semantics.builder.model.Concept;
import org.drools.semantics.builder.model.OntoModel;
import org.drools.semantics.builder.model.PropertyRelation;
import org.drools.semantics.builder.model.SubConceptOf;
import org.semanticweb.HermiT.Reasoner;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.util.*;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.*;

public class DelegateInferenceStrategy extends AbstractModelInferenceStrategy {

    private static int counter = 0;

    public static int minCounter = 0;
    public static int maxCounter = 0;

    private InferredOntologyGenerator reasoner;

    private Map<OWLClassExpression,OWLClassExpression> aliases;

    private Map<String, Concept> conceptCache = new HashMap<String, Concept>();
    private Map<OWLClassExpression, OWLClass> fillerCache = new HashMap<OWLClassExpression, OWLClass>();
    private Map<String, String> props = new HashMap<String, String>();



    private static DLFactory.SupportedReasoners externalReasoner = DLFactory.SupportedReasoners.PELLET;
    public static void setExternalReasoner( DLFactory.SupportedReasoners newReasoner ) {
        externalReasoner = newReasoner;
    }



    private MultiKeyMap minCards = new MultiKeyMap();
    private MultiKeyMap maxCards = new MultiKeyMap();

    private static void register( String prim, String klass ) {
        IRI i1 = IRI.create( prim );
        Concept con = new Concept( i1.toQuotedString(), klass );
        con.setPrimitive(true);
        primitives.put( i1.toQuotedString(), con );
    }

    private static Map<String, Concept> primitives = new HashMap<String, Concept>();

    {

        register( "http://www.w3.org/2001/XMLSchema#string", "xsd:string" );

        register( "http://www.w3.org/2001/XMLSchema#dateTime", "xsd:dateTime" );

        register( "http://www.w3.org/2001/XMLSchema#date", "xsd:date" );

        register( "http://www.w3.org/2001/XMLSchema#time", "xsd:time" );

        register( "http://www.w3.org/2001/XMLSchema#int", "xsd:int" );

        register( "http://www.w3.org/2001/XMLSchema#integer", "xsd:integer" );

        register( "http://www.w3.org/2001/XMLSchema#long", "xsd:long" );

        register( "http://www.w3.org/2001/XMLSchema#float", "xsd:float" );

        register( "http://www.w3.org/2001/XMLSchema#double", "xsd:double" );

        register( "http://www.w3.org/2001/XMLSchema#short", "xsd:short" );

        register( "http://www.w3.org/2000/01/rdf-schema#Literal", "xsd:anySimpleType" );

        register( "http://www.w3.org/2001/XMLSchema#boolean", "xsd:boolean" );

        register( "http://www.w3.org/2001/XMLSchema#decimal", "xsd:decimal" );

        register( "http://www.w3.org/2001/XMLSchema#byte", "xsd:byte" );

        register( "http://www.w3.org/2001/XMLSchema#unsignedByte", "xsd:unsignedByte" );

        register( "http://www.w3.org/2001/XMLSchema#unsignedShort", "xsd:unsignedShort" );

        register( "http://www.w3.org/2001/XMLSchema#unsignedInt", "xsd:unsignedInt" );
    }


    @Override
    protected OntoModel buildProperties( OWLOntology ontoDescr, StatefulKnowledgeSession kSession, Map<InferenceTask, Resource> theory, OntoModel hierarchicalModel ) {

        OWLDataFactory factory = ontoDescr.getOWLOntologyManager().getOWLDataFactory();

        fillPropNamesInDataStructs( ontoDescr );

        // Complete missing domains and ranges for properties. Might be overridden later, if anything can be inferred
        createAndAddBasicProperties( ontoDescr, factory, hierarchicalModel );

        // Apply any cardinality / range restriction
        applyPropertyRestrictions( ontoDescr, hierarchicalModel );

        System.out.println( hierarchicalModel );


        return hierarchicalModel;
    }



    private void applyPropertyRestrictions( OWLOntology ontoDescr, OntoModel hierarchicalModel ) {

        for ( PropertyRelation prop : hierarchicalModel.getProperties() ) {
            if ( prop.getTarget() == null ) {
                System.err.println("WARNING : Prperty without target concept " +  prop.getName() );
            }
        }


        Map<String, Set<OWLClassExpression>> supers = new HashMap<String, Set<OWLClassExpression>>();
        for ( OWLClass klass : ontoDescr.getClassesInSignature() ) {
            supers.put( klass.getIRI().toQuotedString(), new HashSet<OWLClassExpression>() );
        }
        for ( OWLClass klass : ontoDescr.getClassesInSignature() ) {
            if ( isDelegating( klass ) ) {
                OWLClassExpression delegate = aliases.get( klass );
                supers.get( delegate.asOWLClass().getIRI().toQuotedString() ).addAll( klass.getSuperClasses( ontoDescr ) );
            } else {
                supers.get( klass.asOWLClass().getIRI().toQuotedString() ).addAll( klass.getSuperClasses( ontoDescr ) );
            }

        }

        for ( Concept con : hierarchicalModel.getConcepts() ) {      //use concepts as they're sorted!


            if ( isDelegating( con.getIri() ) ) {
                continue;
            }

            if ( con == null ) {
                System.err.println("WARNING : Looking for superclasses of an undefined concept");
                continue;
            }

            LinkedList<OWLClassExpression> orderedSupers = new LinkedList<OWLClassExpression>();
            // cardinalities should be fixed last
            for ( OWLClassExpression sup : supers.get( con.getIri() ) ) {
                if ( sup instanceof OWLCardinalityRestriction ) {
                    orderedSupers.addLast( sup );
                } else {
                    orderedSupers.addFirst( sup );
                }
            }

            for ( OWLClassExpression sup : orderedSupers ) {
                if ( sup.isClassExpressionLiteral() ) {
                    continue;
                }


                processSuperClass( sup, con, hierarchicalModel );
            }
        }

    }

    private void processSuperClass(OWLClassExpression sup, Concept con, OntoModel hierarchicalModel ) {
        String propIri;
        PropertyRelation rel;
        Concept tgt;
        switch ( sup.getClassExpressionType() ) {
            case DATA_SOME_VALUES_FROM:
                //check that it's a subclass of the domain. Should have already been done, or it would be an inconsistency
                break;
            case DATA_ALL_VALUES_FROM:
                OWLDataAllValuesFrom forall = (OWLDataAllValuesFrom) sup;
                propIri = forall.getProperty().asOWLDataProperty().getIRI().toQuotedString();
                tgt = primitives.get( forall.getFiller().asOWLDatatype().getIRI().toQuotedString()  );
                rel = extractProperty( con, propIri, tgt );
                if ( rel != null ) {
                    rel.setObject( forall.getFiller().asOWLDatatype().getIRI().toQuotedString()  );
                    rel.setTarget( primitives.get( forall.getFiller().asOWLDatatype().getIRI().toQuotedString() ) );
                    con.addProperty( propIri, props.get( propIri ), rel );
                    hierarchicalModel.addProperty( rel );
                } else {
                    System.err.println("WARNING : Could not find property " + propIri + " restricted in class " + con.getIri() );
                }
                break;
            case DATA_MIN_CARDINALITY:
                OWLDataMinCardinality min = (OWLDataMinCardinality) sup;
                propIri = min.getProperty().asOWLDataProperty().getIRI().toQuotedString();
                tgt = primitives.get( min.getFiller().asOWLDatatype().getIRI().toQuotedString()  );
                rel = extractProperty( con, propIri, tgt );
                if ( rel != null ) {
                    rel.setMinCard( min.getCardinality() );
                } else {
                    System.err.println("WARNING : Could not find property " + propIri + " restricted in class " + con.getIri() );
                }
                break;
            case DATA_MAX_CARDINALITY:
                OWLDataMaxCardinality max = (OWLDataMaxCardinality) sup;
                propIri = max.getProperty().asOWLDataProperty().getIRI().toQuotedString();
                tgt = primitives.get( max.getFiller().asOWLDatatype().getIRI().toQuotedString()  );
                rel = extractProperty( con, propIri, tgt );
                if ( rel != null ) {
                    rel.setMaxCard( max.getCardinality() );
                } else {
                    System.err.println("WARNING : Could not find property " + propIri + " restricted in class " + con.getIri() );
                }
                break;
            case DATA_EXACT_CARDINALITY:
                OWLDataExactCardinality ex = (OWLDataExactCardinality) sup;
                propIri = ex.getProperty().asOWLDataProperty().getIRI().toQuotedString();
                tgt = primitives.get( ex.getFiller().asOWLDatatype().getIRI().toQuotedString()  );
                rel = extractProperty( con, propIri, tgt );
                if ( rel != null ) {
                    rel.setMinCard( ex.getCardinality() );
                    rel.setMaxCard( ex.getCardinality() );
                } else {
                    System.err.println("WARNING : Could not find property " + propIri + " restricted in class " + con.getIri() );
                }
                break;
            case OBJECT_SOME_VALUES_FROM:
                OWLObjectSomeValuesFrom someO = (OWLObjectSomeValuesFrom) sup;
                propIri = someO.getProperty().asOWLObjectProperty().getIRI().toQuotedString();
                if ( filterAliases( someO.getFiller() ).isAnonymous() ) {
                    System.err.println( "WARNING : Complex unaliased restriction " + someO );
                    break;
                }
                tgt = conceptCache.get( filterAliases( someO.getFiller() ).asOWLClass().getIRI().toQuotedString() );
                rel = extractProperty( con, propIri, tgt );
                if ( rel != null ) {
                    rel.setMinCard( Math.max( rel.getMinCard(), 1 ) );
//                    rel.setObject( tgt.getName() );
//                    rel.setTarget( tgt );
//                    con.addProperty( propIri, props.get( propIri ), rel );
                    hierarchicalModel.addProperty( rel );
                } else {
                    System.err.println("WARNING : Could not find property " + propIri + " restricted in class " + con.getIri() );
                }
                break;
            case OBJECT_ALL_VALUES_FROM:
                OWLObjectAllValuesFrom forallO = (OWLObjectAllValuesFrom) sup;
                propIri = forallO.getProperty().asOWLObjectProperty().getIRI().toQuotedString();
                if ( filterAliases( forallO.getFiller() ).isAnonymous() ) {
                    System.err.println( "WARNING : Complex unaliased restriction " + forallO );
                    break;
                }
                tgt = conceptCache.get( filterAliases( forallO.getFiller() ).asOWLClass().getIRI().toQuotedString() );
                rel = extractProperty( con, propIri, tgt, true );
                if ( rel != null ) {
//                    rel.setObject( tgt.getName() );
//                    rel.setTarget( tgt );
//                    con.addProperty( propIri, props.get( propIri ), rel );
                    hierarchicalModel.addProperty( rel );
                } else {
                    System.err.println("WARNING : Could not find property " + propIri + " restricted in class " + con.getIri() );
                }
                break;
            case OBJECT_MIN_CARDINALITY:
                OWLObjectMinCardinality minO = (OWLObjectMinCardinality) sup;
                propIri = minO.getProperty().asOWLObjectProperty().getIRI().toQuotedString();
                tgt = conceptCache.get( filterAliases( minO.getFiller() ).asOWLClass().getIRI().toQuotedString() );
                rel = extractProperty( con, propIri, tgt );
                if ( rel != null ) {
                    rel.setMinCard( minO.getCardinality() );
                } else {
                    System.err.println("WARNING : Could not find property " + propIri + " restricted in class " + con.getIri() );
                }
                break;
            case OBJECT_MAX_CARDINALITY:
                OWLObjectMaxCardinality maxO = (OWLObjectMaxCardinality) sup;
                propIri = maxO.getProperty().asOWLObjectProperty().getIRI().toQuotedString();
                tgt = conceptCache.get( filterAliases( maxO.getFiller() ).asOWLClass().getIRI().toQuotedString() );
                rel = extractProperty( con, propIri, tgt );
                if ( rel != null ) {
                    rel.setMaxCard( maxO.getCardinality() );
                } else {
                    System.err.println("WARNING : Could not find property " + propIri + " restricted in class " + con.getIri() );
                }
                break;
            case OBJECT_EXACT_CARDINALITY:
                OWLObjectExactCardinality exO = (OWLObjectExactCardinality) sup;
                propIri = exO.getProperty().asOWLObjectProperty().getIRI().toQuotedString();
                tgt = conceptCache.get( filterAliases( exO.getFiller() ).asOWLClass().getIRI().toQuotedString() );
                rel = extractProperty( con, propIri, tgt );
                if ( rel != null ) {
                    rel.setMinCard( exO.getCardinality() );
                    rel.setMaxCard( exO.getCardinality() );
                } else {
                    System.err.println("WARNING : Could not find property " + propIri + " restricted in class " + con.getIri() );
                }
                break;
            case OBJECT_INTERSECTION_OF:
                OWLObjectIntersectionOf and = (OWLObjectIntersectionOf) sup;
                for ( OWLClassExpression arg : and.asConjunctSet() ) {
                    processSuperClass( arg, con, hierarchicalModel );
                }
            default:
                System.err.println("WARNING : Cannot handle " + sup );
        }

    }

    private PropertyRelation extractProperty( Concept con, String propIri, Concept target ) {
        return extractProperty( con, propIri, target, false );
    }

    private PropertyRelation extractProperty( Concept con, String propIri, Concept target, boolean override ) {
        override = false;
        if ( target == null ) {
            System.err.println( "WARNING : Null target for property " + propIri );
        }

        String restrictedPropIri = propIri.replace(">", target.getName()+">");
        PropertyRelation rel = con.getProperties().get( propIri );
        if ( rel == null ) {
            rel = con.getProperties().get( restrictedPropIri );
        }

        if ( rel == null ) {

            rel = inheritPropertyCopy( con, con, propIri );

            if ( rel != null ) {
                rel.setRestricted( true );
                if ( ! rel.getTarget().equals( target ) ) {
                    rel.setSubject( con.getName() );

                    if ( override ) {
                        rel.setSubject( con.getName() );
                        con.addProperty( propIri, props.get( propIri ), rel );

                    } else {
                        rel.setName( rel.getName() + target.getName() );
                        rel.setProperty( restrictedPropIri );
                        con.addProperty( restrictedPropIri, props.get( propIri ) + target.getName(), rel );

                    }
                    rel.setTarget( target );
                    rel.setObject( target.getIri() );

                    System.err.println( "INFO : RESTRICTED property " + propIri + " to " + target );
                } else {
                    rel.setSubject( con.getName() );
                    con.addProperty( propIri, props.get( propIri ), rel );
                }
            }
        }
        return rel;
    }




    private PropertyRelation inheritPropertyCopy( Concept original, Concept current, String propIri ) {
        PropertyRelation rel;
        for ( Concept sup : current.getSuperConcepts() ) {
            System.err.println( "Looking for " +propIri + " among the ancestors of " + current.getName() + ", now try " + sup.getName() );
            rel = sup.getProperties().get( propIri );
            if ( rel != null ) {
                PropertyRelation restrictedRel = new PropertyRelation( rel.getSubject(), rel.getProperty(), rel.getObject(), rel.getName() );
                restrictedRel.setMinCard( rel.getMinCard() );
                restrictedRel.setMaxCard( rel.getMaxCard() );
                restrictedRel.setTarget( rel.getTarget() );
                System.err.println( "Found " +propIri + " in " + sup.getName() );
                return restrictedRel;
            } else {
                rel = inheritPropertyCopy( original, sup, propIri );
                if ( rel != null ) {
                    return rel;
                }
            }
        }
        return null;

    }


    private void createAndAddBasicProperties(OWLOntology ontoDescr, OWLDataFactory factory, OntoModel hierarchicalModel ) {
        int missDomain = 0;
        int missDataRange = 0;
        int missObjectRange = 0;

        for ( OWLDataProperty dp : ontoDescr.getDataPropertiesInSignature() ) {
            if ( ! dp.isOWLTopDataProperty() && ! dp.isOWLBottomDataProperty() ) {
                Set<OWLClassExpression> domains = dp.getDomains( ontoDescr );
                if ( domains.isEmpty() ) {
                    domains.add( factory.getOWLThing() );
                    System.err.println( "WARNING Added missing domain for" + dp);
                    missDomain++;
                }
                Set<OWLDataRange> ranges = dp.getRanges( ontoDescr );
                if ( ranges.isEmpty() ) {
                    ranges.add( factory.getRDFPlainLiteral() );
                    System.err.println( "WARNING Added missing range for" + dp);
                    missDataRange++;
                }

                for ( OWLClassExpression domain : domains ) {
                    for ( OWLDataRange range : ranges ) {
                        OWLClassExpression realDom = filterAliases( domain );
                        PropertyRelation rel = new PropertyRelation( realDom.asOWLClass().getIRI().toQuotedString(),
                                dp.getIRI().toQuotedString(),
                                range.asOWLDatatype().getIRI().toQuotedString(),
                                props.get( dp.getIRI().toQuotedString() ) );

                        Concept con = conceptCache.get( rel.getSubject() );
                        rel.setTarget( primitives.get( rel.getObject() ) );

                        con.addProperty( rel.getProperty(), rel.getName(), rel );
                        hierarchicalModel.addProperty( rel );
                    }
                }
            }
        }


        for ( OWLObjectProperty op : ontoDescr.getObjectPropertiesInSignature() ) {
            if ( ! op.isOWLTopObjectProperty() && ! op.isOWLBottomObjectProperty() ) {
                Set<OWLClassExpression> domains = op.getDomains( ontoDescr );
                if ( domains.isEmpty() ) {
                    domains.add( factory.getOWLThing() );
                    System.err.println( "WARNING Added missing domain for" + op);
                    missDomain++;
                }
                Set<OWLClassExpression> ranges = op.getRanges( ontoDescr );
                if ( ranges.isEmpty() ) {
                    ranges.add( factory.getOWLThing() );
                    System.err.println( "WARNING Added missing range for" + op);
                    missObjectRange++;
                }

                for ( OWLClassExpression domain : domains ) {
                    for ( OWLClassExpression range : ranges ) {
                        OWLClassExpression realDom = filterAliases( domain );
                        OWLClassExpression realRan = filterAliases( range );

                        PropertyRelation rel = new PropertyRelation( realDom.asOWLClass().getIRI().toQuotedString(),
                                op.getIRI().toQuotedString(),
                                realRan.asOWLClass().getIRI().toQuotedString(),
                                props.get( op.getIRI().toQuotedString() ) );

                        Concept con = conceptCache.get( rel.getSubject() );
                        rel.setTarget( conceptCache.get(rel.getObject()) );

                        con.addProperty( rel.getProperty(), rel.getName(), rel );
                        hierarchicalModel.addProperty( rel );
                    }
                }
            }
        }

        System.err.println("Misses : ");
        System.err.println(missDomain);
        System.err.println(missDataRange);
        System.err.println(missObjectRange);
    }


    private Map<OWLClassExpression,OWLClassExpression> buildAliasesForEquivalentClasses(OWLOntology ontoDescr) {
        Map<OWLClassExpression,OWLClassExpression> aliases = new HashMap<OWLClassExpression,OWLClassExpression>();
        Set<OWLEquivalentClassesAxiom> pool = new HashSet<OWLEquivalentClassesAxiom>();
        Set<OWLEquivalentClassesAxiom> temp = new HashSet<OWLEquivalentClassesAxiom>();


        for ( OWLClass klass : ontoDescr.getClassesInSignature() ) {
            for ( OWLEquivalentClassesAxiom klassEq : ontoDescr.getEquivalentClassesAxioms( klass ) ) {
                for (OWLEquivalentClassesAxiom eq2 : klassEq.asPairwiseAxioms() ) {
                    pool.add( eq2 );
                }
            }
        }

        boolean stable = false;
        while ( ! stable ) {
            stable = true;
            temp.addAll( pool );
            for ( OWLEquivalentClassesAxiom eq2 : pool ) {

                List<OWLClassExpression> pair = eq2.getClassExpressionsAsList();
                OWLClassExpression first = pair.get( 0 );
                OWLClassExpression secnd = pair.get( 1 );
                OWLClassExpression removed = null;

                if ( aliases.containsValue( first ) ) {
                    // add to existing eqSet, put reversed
                    // A->X,  add X->C          ==> A->X, C->X
                    removed = aliases.put( secnd, first );
                    System.out.println("TAAKING OUT 1" + eq2 );
                    if ( removed != null ) {
                        System.err.println( "WARNING : DOUBLE KEY WHILE RESOLVING EQUALITIES" + removed + " for value " + eq2 );
                    }

                    stable = false;
                    temp.remove( eq2 );
                } else if ( aliases.containsValue( secnd ) ) {
                    // add to existing eqSet, put as is
                    // A->X,  add C->X          ==> A->X, C->X
                    removed = aliases.put( first, secnd );
                    System.out.println("TAAKING OUT 2" + eq2 );
                    if ( removed != null ) {
                        System.err.println( "WARNING : DOUBLE KEY WHILE RESOLVING EQUALITIES" + removed + " for value " + eq2 );
                    }

                    stable = false;
                    temp.remove( eq2 );
                } else if ( aliases.containsKey( first ) ) {
                    // apply transitivity, reversed
                    // A->X,  add A->C          ==> A->X, C->X
                    removed = aliases.put( secnd, aliases.get( first ) );
                    System.out.println("TAAKING OUT 3" + eq2 );
                    if ( removed != null ) {
                        System.err.println( "WARNING : DOUBLE KEY WHILE RESOLVING EQUALITIES" + removed + " for value " + eq2 );
                    }

                    stable = false;
                    temp.remove( eq2 );
                } else if ( aliases.containsKey( secnd ) ) {
                    // apply transitivity, as is
                    // A->X,  add C->A          ==> A->X, C->X
                    removed = aliases.put( first, aliases.get( secnd ) );
                    System.out.println("TAAKING OUT 4" + eq2 );
                    if ( removed != null ) {
                        System.err.println( "WARNING : DOUBLE KEY WHILE RESOLVING EQUALITIES" + removed + " for value " + eq2 );
                    }

                    stable = false;
                    temp.remove( eq2 );
                } else if ( ! first.isAnonymous() && ! isAbstract( first )  ) {
                    removed = aliases.put( secnd, first );
                    System.out.println("TAAKING OUT 5" + eq2 );
                    if ( removed != null ) {
                        System.err.println( "WARNING : DOUBLE KEY WHILE RESOLVING EQUALITIES" + removed + " for value " + eq2 );
                    }

                    stable = false;
                    temp.remove( eq2 );
                } else if ( ! secnd.isAnonymous() && ! isAbstract( secnd ) ) {
                    removed = aliases.put( first, secnd );
                    System.out.println("TAAKING OUT 6" + eq2 );
                    if ( removed != null ) {
                        System.err.println( "WARNING : DOUBLE KEY WHILE RESOLVING EQUALITIES" + removed + " for value " + eq2 );
                    }

                    stable = false;
                    temp.remove( eq2 );
                } else {
                    // both anonymous
                }


            }
            pool.clear();
            pool.addAll(temp);
        }

        if ( ! pool.isEmpty() ) {
            System.err.println( "WARNING : COULD NOT RESOLVE ANON=ANON EQUALITIES " + pool );
            for ( OWLEquivalentClassesAxiom eq2 : pool ) {
                List<OWLClassExpression> l = eq2.getClassExpressionsAsList();
                if ( ! (l.get(0).isAnonymous() && l.get(1).isAnonymous())) {
                    System.err.println( "WARNING : " + l + " EQUALITY WAS NOT RESOLVED" );
                }
            }
        }


        System.out.println("----------------------------------------------------------------------- " + aliases.size());
        for(Map.Entry<OWLClassExpression,OWLClassExpression> entry : aliases.entrySet()) {
            System.out.println( entry.getKey() + " == " + entry.getValue() );
        }
        System.out.println("-----------------------------------------------------------------------");

        return aliases;
    }

    private boolean isAbstract(OWLClassExpression clax) {
        return  clax.asOWLClass().getIRI().toQuotedString().contains("Filler");
    }


    private void launchReasoner( boolean dirty, StatefulKnowledgeSession kSession, OWLOntology ontoDescr ) {
        if ( dirty ) {
            System.err.println( " START REASONER " );
            initReasoner(kSession, ontoDescr);
            reasoner.fillOntology( ontoDescr.getOWLOntologyManager(), ontoDescr );
            System.err.println( " STOP REASONER " );
        } else {
            System.err.println( " REASONER NOT NEEDED" );
        }


    }


    private boolean processComplexDomainAndRanges(OWLOntology ontoDescr, OWLDataFactory factory) {
        boolean dirty = false;
        dirty |= processComplexDataPropertyDomains( ontoDescr, factory );
        dirty |= processComplexObjectPropertyDomains( ontoDescr, factory );
        dirty |= processComplexObjectPropertyRanges( ontoDescr, factory );
        return dirty;
    }


    private boolean processComplexObjectPropertyDomains(OWLOntology ontoDescr, OWLDataFactory factory ) {
        boolean dirty = false;
        for ( OWLObjectProperty op : ontoDescr.getObjectPropertiesInSignature() ) {
            String typeName = DLUtils.buildNameFromIri(op.getIRI());

            Set<OWLObjectPropertyDomainAxiom> domains = ontoDescr.getObjectPropertyDomainAxioms( op );
            if ( domains.size() > 1 ) {
                Set<OWLClassExpression> domainClasses = new HashSet<OWLClassExpression>();
                for ( OWLObjectPropertyDomainAxiom dom : domains ) {
                    domainClasses.add( dom.getDomain() );
                }
                OWLObjectIntersectionOf and = factory.getOWLObjectIntersectionOf( domainClasses );

                for ( OWLObjectPropertyDomainAxiom dom : domains ) {
                    ontoDescr.getOWLOntologyManager().applyChange( new RemoveAxiom( ontoDescr, dom ) );
                }
                ontoDescr.getOWLOntologyManager().applyChange( new AddAxiom( ontoDescr, factory.getOWLObjectPropertyDomainAxiom( op, and ) ) );
                dirty = true;
            }

            if ( op.getDomains( ontoDescr ).size() > 1 ) {
                System.err.println(" WARNING : Property " + op + " should have a single domain class, found " + op.getDomains( ontoDescr ) );
            }

            for ( OWLClassExpression dom : op.getDomains( ontoDescr ) ) {
                if ( dom.isAnonymous() ) {
                    OWLClass domain = factory.getOWLClass(IRI.create( typeName + "Domain") );
                    System.out.println("REPLACED ANON DOMAIN " + op + " with " + domain + ", was " + dom);
                    ontoDescr.getOWLOntologyManager().applyChange( new AddAxiom( ontoDescr, factory.getOWLDeclarationAxiom( domain ) ) );
                    ontoDescr.getOWLOntologyManager().applyChange( new AddAxiom( ontoDescr, factory.getOWLEquivalentClassesAxiom( domain, dom ) ) );
                    ontoDescr.getOWLOntologyManager().applyChange( new RemoveAxiom( ontoDescr, ontoDescr.getObjectPropertyDomainAxioms( op ).iterator().next() ) );
                    ontoDescr.getOWLOntologyManager().applyChange( new AddAxiom( ontoDescr, factory.getOWLObjectPropertyDomainAxiom( op, domain ) ) );
                    dirty = true;
                }
            }

        }
        return dirty;
    }


    private boolean processComplexDataPropertyDomains(OWLOntology ontoDescr, OWLDataFactory factory ) {
        boolean dirty = false;
        for ( OWLDataProperty dp : ontoDescr.getDataPropertiesInSignature() ) {
            String typeName = DLUtils.buildNameFromIri(dp.getIRI());

            Set<OWLDataPropertyDomainAxiom> domains = ontoDescr.getDataPropertyDomainAxioms(dp);
            if ( domains.size() > 1 ) {
                Set<OWLClassExpression> domainClasses = new HashSet<OWLClassExpression>();
                for ( OWLDataPropertyDomainAxiom dom : domains ) {
                    domainClasses.add( dom.getDomain() );
                }
                OWLObjectIntersectionOf and = factory.getOWLObjectIntersectionOf( domainClasses );

                for ( OWLDataPropertyDomainAxiom dom : domains ) {
                    ontoDescr.getOWLOntologyManager().applyChange( new RemoveAxiom( ontoDescr, dom ) );
                }
                ontoDescr.getOWLOntologyManager().applyChange( new AddAxiom( ontoDescr, factory.getOWLDataPropertyDomainAxiom(dp, and) ) );
                dirty = true;
            }

            if ( dp.getDomains( ontoDescr ).size() > 1 ) {
                System.err.println(" WARNING : Property " + dp + " should have a single domain class, found " + dp.getDomains( ontoDescr ) );
            }

            for ( OWLClassExpression dom : dp.getDomains( ontoDescr ) ) {
                if ( dom.isAnonymous() ) {
                    OWLClass domain = factory.getOWLClass(IRI.create( typeName + "Domain") );
                    System.out.println("INFO : REPLACED ANON DOMAIN " + dp + " with " + domain + ", was " + dom);
                    ontoDescr.getOWLOntologyManager().applyChange( new AddAxiom( ontoDescr, factory.getOWLDeclarationAxiom( domain ) ) );
                    ontoDescr.getOWLOntologyManager().applyChange( new AddAxiom( ontoDescr, factory.getOWLEquivalentClassesAxiom( domain, dom ) ) );
                    ontoDescr.getOWLOntologyManager().applyChange( new RemoveAxiom( ontoDescr, ontoDescr.getDataPropertyDomainAxioms(dp).iterator().next() ) );
                    ontoDescr.getOWLOntologyManager().applyChange( new AddAxiom( ontoDescr, factory.getOWLDataPropertyDomainAxiom(dp, domain) ) );
                    dirty = true;
                }
            }

        }
        return dirty;
    }


    private boolean processComplexObjectPropertyRanges( OWLOntology ontoDescr, OWLDataFactory factory ) {
        System.out.println("Im in");
        boolean dirty = false;
        for ( OWLObjectProperty op : ontoDescr.getObjectPropertiesInSignature() ) {
            String typeName = DLUtils.buildNameFromIri(op.getIRI());

            Set<OWLObjectPropertyRangeAxiom> ranges = ontoDescr.getObjectPropertyRangeAxioms(op);
            if ( ranges.size() > 1 ) {
                Set<OWLClassExpression> rangeClasses = new HashSet<OWLClassExpression>();
                for ( OWLObjectPropertyRangeAxiom dom : ranges ) {
                    rangeClasses.add( dom.getRange() );
                }
                OWLObjectIntersectionOf and = factory.getOWLObjectIntersectionOf( rangeClasses );

                for ( OWLObjectPropertyRangeAxiom ran : ranges ) {
                    ontoDescr.getOWLOntologyManager().applyChange( new RemoveAxiom( ontoDescr, ran ) );
                }
                ontoDescr.getOWLOntologyManager().applyChange( new AddAxiom( ontoDescr, factory.getOWLObjectPropertyRangeAxiom(op, and) ) );
                dirty = true;
            }

            if ( op.getRanges(ontoDescr).size() > 1 ) {
                System.err.println(" WARNING : Property " + op + " should have a single range class, found " + op.getRanges(ontoDescr) );
            }

            for ( OWLClassExpression ran : op.getRanges(ontoDescr) ) {
                if ( ran.isAnonymous() ) {
                    OWLClass range = factory.getOWLClass(IRI.create( typeName + "Range") );
                    System.out.println("INFO : REPLACED ANON RANGE " + op + " with " + range + ", was " + ran );
                    ontoDescr.getOWLOntologyManager().applyChange( new AddAxiom( ontoDescr, factory.getOWLDeclarationAxiom( range ) ) );
                    ontoDescr.getOWLOntologyManager().applyChange( new AddAxiom( ontoDescr, factory.getOWLEquivalentClassesAxiom( range, ran ) ) );
                    ontoDescr.getOWLOntologyManager().applyChange( new RemoveAxiom( ontoDescr, ontoDescr.getObjectPropertyRangeAxioms(op).iterator().next() ) );
                    ontoDescr.getOWLOntologyManager().applyChange( new AddAxiom( ontoDescr, factory.getOWLObjectPropertyRangeAxiom(op, range) ) );
                    dirty = true;
                }
            }

        }
        return dirty;
    }


    private void processQuantifiedRestrictions( OWLOntology ontoDescr, OWLDataFactory factory ) {
        // infer domain / range from quantified restrictions...
        for ( OWLClass klassAx : ontoDescr.getClassesInSignature() ) {
            OWLClass klass = klassAx;
//            System.out.println("Taking a look at " + klass);

            for ( OWLClassExpression clax : klass.getSuperClasses( ontoDescr ) ) {
                clax = clax.getNNF();
//                System.out.println("\thas SuperXompakt " + clax);
                processQuantifiedRestrictionsInClass( klass, clax, ontoDescr, factory );
            }

            for ( OWLClassExpression clax : klass.getEquivalentClasses( ontoDescr ) ) {
                clax = clax.getNNF();
//                System.out.println("\thas SuperXompakt " + clax);
                processQuantifiedRestrictionsInClass( klass, clax, ontoDescr, factory );
            }
        }

    }

    private void processQuantifiedRestrictionsInClass(OWLClass klass, OWLClassExpression clax, OWLOntology ontology, OWLDataFactory fac) {
        final OWLClass inKlass = klass;
        final OWLDataFactory factory = fac;
        final OWLOntology ontoDescr = ontology;



        clax.accept( new OWLClassExpressionVisitor() {



            private void process( OWLClassExpression expr ) {
//                System.out.println("\t\t\t Visiting " + expr);

                if ( expr instanceof OWLNaryBooleanClassExpression ) {
                    for (OWLClassExpression clax : ((OWLNaryBooleanClassExpression) expr).getOperandsAsList() ) {
                        process( clax );
                    }
                } else if ( expr instanceof OWLQuantifiedObjectRestriction  ) {

                    OWLQuantifiedObjectRestriction rest = (OWLQuantifiedObjectRestriction) expr;
                    OWLObjectProperty prop = rest.getProperty().asOWLObjectProperty();
                    OWLClassExpression fil = rest.getFiller();
                    if ( fil.isAnonymous() ) {
                        System.out.println("INFO :MARKED ANON FILLER OF" + rest );
                        OWLClass filler = fillerCache.get( fil );

                        if ( filler == null ) {
                            String fillerName = DLUtils.capitalize( inKlass.getIRI().getFragment() ) +
                                    DLUtils.capitalize( prop.getIRI().getFragment() ) +
                                    "Filler" +
                                    (counter++);
                            filler = factory.getOWLClass( IRI.create( fillerName ) );
                            fillerCache.put( fil, filler );
                        } else {
                            System.out.println("INFO : REUSED FILLER FOR" + fil );
                        }

                        ontoDescr.getOWLOntologyManager().applyChange(new AddAxiom(ontoDescr, factory.getOWLDeclarationAxiom(filler)));
                        ontoDescr.getOWLOntologyManager().applyChange( new AddAxiom( ontoDescr, factory.getOWLEquivalentClassesAxiom( filler, fil ) ) );

                    }

                    process( fil );


                } else if (  expr instanceof  OWLQuantifiedDataRestriction ) {



                } else if ( expr instanceof OWLCardinalityRestriction ) {
                    if ( expr instanceof OWLDataMinCardinality ) {
                        minCards.put(inKlass.getIRI().toQuotedString(),
                                ((OWLDataMinCardinality) expr).getProperty().asOWLDataProperty().getIRI().toQuotedString(),
                                ((OWLDataMinCardinality) expr).getCardinality());
                        minCounter++;
                    } else if ( expr instanceof OWLDataMaxCardinality ) {
                        maxCards.put( inKlass.getIRI().toQuotedString(),
                                ((OWLDataMaxCardinality) expr).getProperty().asOWLDataProperty().getIRI().toQuotedString(),
                                ((OWLDataMaxCardinality) expr).getCardinality() );
                        maxCounter++;
                    } else if ( expr instanceof OWLObjectMaxCardinality ) {
                        maxCards.put( inKlass.getIRI().toQuotedString(),
                                ((OWLObjectMaxCardinality) expr).getProperty().asOWLObjectProperty().getIRI().toQuotedString(),
                                ((OWLObjectMaxCardinality) expr).getCardinality() );
                        process( ((OWLObjectMaxCardinality) expr).getFiller() );
                        maxCounter++;
                    } else if ( expr instanceof OWLObjectMinCardinality ) {
                        minCards.put( inKlass.getIRI().toQuotedString(),
                                ((OWLObjectMinCardinality) expr).getProperty().asOWLObjectProperty().getIRI().toQuotedString(),
                                ((OWLObjectMinCardinality) expr).getCardinality() );
                        process( ((OWLObjectMinCardinality) expr).getFiller() );
                        minCounter++;
                    }
                    //                            System.out.println( expr );
                }
                else return;
            }

            public void visit(OWLClass ce) {
                process( ce );
            }

            public void visit(OWLObjectIntersectionOf ce) {
                process( ce );
            }

            public void visit(OWLObjectUnionOf ce) {
                process( ce );
            }

            public void visit(OWLObjectComplementOf ce) {
                process( ce );
            }

            public void visit(OWLObjectSomeValuesFrom ce) {
                process(ce);
            }

            public void visit(OWLObjectAllValuesFrom ce) {
                process(ce);
            }

            public void visit(OWLObjectHasValue ce) {
                process(ce);
            }

            public void visit(OWLObjectMinCardinality ce) {
                //                        minCards.put(inKlass, ce.getProperty().asOWLObjectProperty().getIRI().toQuotedString(), ce.getCardinality());
                //                        minCounter++;
                process(ce);
            }

            public void visit(OWLObjectExactCardinality ce) {
                //                        maxCards.put(inKlass, ce.getProperty().asOWLObjectProperty().getIRI().toQuotedString(), ce.getCardinality());
                //                        minCards.put(inKlass, ce.getProperty().asOWLObjectProperty().getIRI().toQuotedString(), ce.getCardinality());
                //                        minCounter++;
                //                        maxCounter++;
                process(ce);
            }

            public void visit(OWLObjectMaxCardinality ce) {
                //                        maxCards.put(inKlass, ce.getProperty().asOWLObjectProperty().getIRI().toQuotedString(), ce.getCardinality());
                //                        maxCounter++;
                process(ce);
            }

            public void visit(OWLObjectHasSelf ce) {
                throw new UnsupportedOperationException();
            }

            public void visit(OWLObjectOneOf ce) {
                throw new UnsupportedOperationException();
            }

            public void visit(OWLDataSomeValuesFrom ce) {
                process(ce);
            }

            public void visit(OWLDataAllValuesFrom ce) {
                process(ce);
            }

            public void visit(OWLDataHasValue ce) {
                process(ce);
            }

            public void visit(OWLDataMinCardinality ce) {
                //                        minCards.put(inKlass, ce.getProperty().asOWLDataProperty().getIRI().toQuotedString(), ce.getCardinality());
                //                        minCounter++;
                process(ce);
            }

            public void visit(OWLDataExactCardinality ce) {
                //                        minCards.put(inKlass, ce.getProperty().asOWLDataProperty().getIRI().toQuotedString(), ce.getCardinality());
                //                        maxCards.put(inKlass, ce.getProperty().asOWLDataProperty().getIRI().toQuotedString(), ce.getCardinality());
                //                        maxCounter++;
                //                        minCounter++;
                process(ce);
            }

            public void visit(OWLDataMaxCardinality ce) {
                //                        maxCards.put(inKlass, ce.getProperty().asOWLDataProperty().getIRI().toQuotedString(), ce.getCardinality());
                //                        maxCounter++;
                process(ce);
            }
        });



    }


    private void fillPropNamesInDataStructs( OWLOntology ontoDescr ) {
        for ( OWLDataProperty dp : ontoDescr.getDataPropertiesInSignature() ) {
            if ( ! dp.isTopEntity() && ! dp.isBottomEntity() ) {
                String propIri = dp.getIRI().toQuotedString();
                String propName = DLUtils.buildLowCaseNameFromIri(dp.getIRI());
                props.put( propIri, propName );
            }
        }

        for ( OWLObjectProperty op : ontoDescr.getObjectPropertiesInSignature() ) {
            if ( ! op.isTopEntity() && ! op.isBottomEntity() ) {
                String propIri = op.getIRI().toQuotedString();
                String propName = DLUtils.buildLowCaseNameFromIri(op.getIRI());
                props.put( propIri, propName );
            }
        }


    }


    private void reportStats(OWLOntology ontoDescr) {

        System.out.println( " *** Stats for ontology  : " + ontoDescr.getOntologyID() );

        System.out.println( " Number of classes " + ontoDescr.getClassesInSignature().size() );
        System.out.println( " \t Number of classes " + ontoDescr.getClassesInSignature().size() );

        System.out.println( " Number of datatypes " + ontoDescr.getDatatypesInSignature().size() );

        System.out.println( " Number of dataProps " + ontoDescr.getDataPropertiesInSignature().size() );
        System.out.println( "\t Number of dataProp domains " + ontoDescr.getAxiomCount( AxiomType.DATA_PROPERTY_DOMAIN ));
        for ( OWLDataProperty p : ontoDescr.getDataPropertiesInSignature() ) {
            int num = ontoDescr.getDataPropertyDomainAxioms( p ).size();
            if ( num != 1 ) {
                System.out.println( "\t\t Domain" + p + " --> " + num );
            } else {
                OWLDataPropertyDomainAxiom dom = ontoDescr.getDataPropertyDomainAxioms( p ).iterator().next();
                if ( ! ( dom.getDomain() instanceof OWLClass ) ) {
                    System.out.println( "\t\t Complex Domain"  + p + " --> " + dom.getDomain() );
                }
            }
        }
        System.out.println( "\t Number of dataProp ranges " + ontoDescr.getAxiomCount( AxiomType.DATA_PROPERTY_RANGE ));
        for ( OWLDataProperty p : ontoDescr.getDataPropertiesInSignature() ) {
            int num = ontoDescr.getDataPropertyRangeAxioms( p ).size();
            if ( num != 1 ) {
                System.out.println( "\t\t Range" + p + " --> " + num );
            } else {
                OWLDataPropertyRangeAxiom range = ontoDescr.getDataPropertyRangeAxioms( p ).iterator().next();
                if ( ! ( range.getRange() instanceof OWLDatatype ) ) {
                    System.out.println( "\t\t Complex Range" + p + " --> " + range.getRange()  );
                }
            }
        }

        System.out.println( " Number of objProps " + ontoDescr.getObjectPropertiesInSignature().size() );
        System.out.println( "\t Number of objProp domains " + ontoDescr.getAxiomCount( AxiomType.OBJECT_PROPERTY_DOMAIN ));
        for ( OWLObjectProperty p : ontoDescr.getObjectPropertiesInSignature() ) {
            int num = ontoDescr.getObjectPropertyDomainAxioms( p ).size();
            if ( num != 1 ) {
                System.out.println( "\t\t Domain" + p + " --> " + num );
            } else {
                OWLObjectPropertyDomainAxiom dom = ontoDescr.getObjectPropertyDomainAxioms( p ).iterator().next();
                if ( ! ( dom.getDomain() instanceof OWLClass ) ) {
                    System.out.println( "\t\t Complex Domain" + p + " --> " + dom.getDomain()  );
                }
            }
        }
        System.out.println( "\t Number of objProp ranges " + ontoDescr.getAxiomCount( AxiomType.OBJECT_PROPERTY_RANGE ));
        for ( OWLObjectProperty p : ontoDescr.getObjectPropertiesInSignature() ) {
            int num = ontoDescr.getObjectPropertyRangeAxioms( p ).size();
            if ( num != 1 ) {
                System.out.println( "\t\t Range" + p + " --> " + num );
            }  else {
                OWLObjectPropertyRangeAxiom range = ontoDescr.getObjectPropertyRangeAxioms( p ).iterator().next();
                if ( ! ( range.getRange() instanceof OWLClass ) ) {
                    System.out.println( "\t\t Complex Domain" + p + " --> " + range.getRange()  );
                }
            }
        }

//        System.exit(0);





    }







    private OWLProperty lookupDataProperty(String propId, Set<OWLDataProperty> set ) {
        for (OWLDataProperty prop : set ) {
            if ( prop.getIRI().toQuotedString().equals( propId ) ) {
                return prop;
            }
        }
        return null;
    }

    private OWLProperty lookupObjectProperty(String propId, Set<OWLObjectProperty> set ) {
        for (OWLObjectProperty prop : set ) {
            if ( prop.getIRI().toQuotedString().equals( propId ) ) {
                return prop;
            }
        }
        return null;
    }


    @Override
    protected OntoModel buildClassLattice(OWLOntology ontoDescr, StatefulKnowledgeSession kSession, Map<InferenceTask, Resource> theory, OntoModel baseModel) {

        boolean dirty = true;
        OWLDataFactory factory = ontoDescr.getOWLOntologyManager().getOWLDataFactory();
        addResource(kSession, theory.get(InferenceTask.CLASS_LATTICE_PRUNE));
        kSession.setGlobal("latticeModel", baseModel);

        launchReasoner( dirty, kSession, ontoDescr );


        // reify complex domains and ranges
        dirty |= processComplexDomainAndRanges( ontoDescr, factory );


        /************************************************************************************************************************************/

        // reify complex restriction fillers
        processQuantifiedRestrictions( ontoDescr, factory );

        /************************************************************************************************************************************/

        // new classes have been added, classify the
        launchReasoner(dirty, kSession, ontoDescr);

        /************************************************************************************************************************************/

        // resolve aliases, choosing delegators
        aliases = buildAliasesForEquivalentClasses(ontoDescr);

        /************************************************************************************************************************************/

        reportStats( ontoDescr );


        // classes are stable now
        addConceptsToModel(kSession, ontoDescr, baseModel);

        // lattice is too. applies aliasing
        addSubConceptsToModel( kSession, ontoDescr, baseModel );




        kSession.fireAllRules();


        return baseModel;
    }





    private void processSubConceptAxiom( OWLClassExpression subClass, OWLClassExpression superClass, StatefulKnowledgeSession kSession, String thing ) {
        if ( ! superClass.isAnonymous() ) {

            String sub = filterAliases( subClass ).asOWLClass().getIRI().toQuotedString();
            String sup = isDelegating( superClass ) ?
                    thing : filterAliases( superClass ).asOWLClass().getIRI().toQuotedString();

            SubConceptOf subcon = new SubConceptOf( sub, sup );
            kSession.insert(subcon);
        } else if ( superClass instanceof OWLObjectIntersectionOf ) {
            OWLObjectIntersectionOf and = (OWLObjectIntersectionOf) superClass;
            for ( OWLClassExpression ex : and.asConjunctSet() ) {
                processSubConceptAxiom( subClass, ex, kSession, thing );
            }
        }
    }

    private void addSubConceptsToModel(StatefulKnowledgeSession kSession, OWLOntology ontoDescr, OntoModel model) {

        kSession.fireAllRules();

        String thing = ontoDescr.getOWLOntologyManager().getOWLDataFactory().getOWLThing().getIRI().toQuotedString();

        for ( OWLSubClassOfAxiom ax : ontoDescr.getAxioms( AxiomType.SUBCLASS_OF ) ) {
            processSubConceptAxiom( ax.getSubClass(), ax.getSuperClass(), kSession, thing );
        }

        for ( OWLClassExpression delegator : aliases.keySet() ) {
            if ( ! delegator.isAnonymous() ) {
                SubConceptOf subcon = new SubConceptOf( delegator.asOWLClass().getIRI().toQuotedString() , thing );
                kSession.insert(subcon);
            }
        }

        for ( OWLClassExpression delegator : aliases.keySet() ) {
            if ( ! delegator.isAnonymous() ) {
                OWLClassExpression delegate = aliases.get( delegator );
                SubConceptOf subcon = new SubConceptOf( delegate.asOWLClass().getIRI().toQuotedString() , delegator.asOWLClass().getIRI().toQuotedString() );
                conceptCache.get(subcon.getSubject()).getEquivalentConcepts().add( conceptCache.get( subcon.getObject() ) );
                kSession.insert(subcon);
            }
        }

        kSession.fireAllRules();

        System.out.println(" >>>>>>>>>> ");
        for ( Object s : kSession.getObjects(new ClassObjectFilter(SubConceptOf.class))) {
            System.out.println( " >>>> " + s );
            SubConceptOf subConceptOf = (SubConceptOf) s;
            model.addSubConceptOf( subConceptOf );
            conceptCache.get(subConceptOf.getSubject()).getSuperConcepts().add(conceptCache.get(subConceptOf.getObject()));
        }
        System.out.println(" >>>>>>>>>> ");
    }

    private boolean isDelegating( OWLClassExpression superClass ) {
        return aliases.containsKey( superClass );
    }

    private boolean isDelegating( String classIri ) {
        for ( OWLClassExpression klass : aliases.keySet() ) {
            if ( ! klass.isAnonymous() && klass.asOWLClass().getIRI().toQuotedString().equals( classIri ) ) {
                return true;
            }
        }
        return false;
    }

    private OWLClassExpression filterAliases( OWLClassExpression klass ) {
        if ( aliases.containsKey( klass ) ) {
            return aliases.get( klass );
        } else return klass;
    }


    private void addConceptsToModel(StatefulKnowledgeSession kSession, OWLOntology ontoDescr, OntoModel baseModel) {
        for ( OWLClass con : ontoDescr.getClassesInSignature() ) {
            if ( baseModel.getConcept( con.getIRI().toQuotedString()) == null ) {
                Concept concept =  new Concept(
                        con.getIRI().toQuotedString(),
                        DLUtils.buildNameFromIri(con.getIRI()) );

                for ( OWLAnnotation ann : con.getAnnotations( ontoDescr ) ) {
                    if ( ann.getProperty().isComment() && ann.getValue() instanceof OWLLiteral ) {
                        OWLLiteral lit = (OWLLiteral) ann.getValue();
                        if ( lit.getLiteral().trim().equals( "abstract" ) ) {
                            concept.setAbstrakt( true );
                        }
                    }
                }

                baseModel.addConcept( concept );
                kSession.insert(concept);
                conceptCache.put(con.getIRI().toQuotedString(), concept);
//                System.out.println(" ADD concept " + con.getIRI() );
            }
        }
    }


    private void addDataRange(Map<OWLProperty, Set<OWLDataRange>> dataRanges, OWLDataProperty dp, OWLDataRange owlData, OWLDataFactory factory ) {
        Set<OWLDataRange> set = dataRanges.get( dp );
        if ( set == null ) {
            set = new HashSet<OWLDataRange>();
            dataRanges.put( dp, set );
        }
        set.add( owlData );
        if ( set.size() >= 2 && set.contains( factory.getTopDatatype() ) ) {
            set.remove( factory.getTopDatatype() );
        }
    }

    private void addRange(Map<OWLProperty, Set<OWLClassExpression>> ranges, OWLProperty rp, OWLClassExpression owlKlass, OWLDataFactory factory ) {
        Set<OWLClassExpression> set = ranges.get( rp );
        if ( set == null ) {
            set = new HashSet<OWLClassExpression>();
            ranges.put( rp, set );
        }
        set.add( owlKlass );
        if ( set.size() >= 2 && set.contains( factory.getOWLThing() ) ) {
            set.remove( factory.getOWLThing() );
        }

    }

    private void addDomain(Map<OWLProperty, Set<OWLClassExpression>> domains, OWLProperty dp, OWLClassExpression owlKlass, OWLDataFactory factory) {
        Set<OWLClassExpression> set = domains.get( dp );
        if ( set == null ) {
            set = new HashSet<OWLClassExpression>();
            domains.put( dp, set );
        }

        set.add( owlKlass );
        if ( set.size() >= 2 && set.contains( factory.getOWLThing() ) ) {
            set.remove( factory.getOWLThing() );
        }
    }



    private void setDomain(Map<OWLProperty, Set<OWLClassExpression>> domains, OWLProperty dp, OWLClassExpression owlKlass, OWLDataFactory factory ) {
        Set<OWLClassExpression> set = domains.get( dp );
        if ( set == null ) {
            set = new HashSet<OWLClassExpression>();
            domains.put( dp, set );
        } else {
            set.clear();
            set.add( owlKlass );
        }
    }

    private void setRange(Map<OWLProperty, Set<OWLClassExpression>> ranges, OWLProperty rp, OWLClassExpression owlKlass, OWLDataFactory factory ) {
        Set<OWLClassExpression> set = ranges.get( rp );
        if ( set == null ) {
            set = new HashSet<OWLClassExpression>();
            ranges.put( rp, set );
        } else {
            set.clear();
            set.add( owlKlass );
        }

    }


    protected void initReasoner( StatefulKnowledgeSession kSession, OWLOntology ontoDescr ) {

        switch ( externalReasoner ) {
            case HERMIT: reasoner = new InferredOntologyGenerator( new Reasoner( ontoDescr ) );
                break;
            case PELLET:
            default:
                reasoner = new InferredOntologyGenerator( PelletReasonerFactory.getInstance().createReasoner( ontoDescr ) ) ;
        }

        reasoner.addGenerator( new InferredSubClassAxiomGenerator() );
        reasoner.addGenerator( new InferredEquivalentClassAxiomGenerator() );
        reasoner.addGenerator( new InferredClassAssertionAxiomGenerator() );
        reasoner.addGenerator( new InferredSubObjectPropertyAxiomGenerator() );
        reasoner.addGenerator( new InferredObjectPropertyCharacteristicAxiomGenerator() );
        reasoner.addGenerator( new InferredPropertyAssertionGenerator() );
    }


}
